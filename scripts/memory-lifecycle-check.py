#!/usr/bin/env python3
"""End-to-end check for memory lifecycle (conflict / reinforcement / decay) and
container-sandbox dispatch.

Prerequisites: the app is running with

  --forgeflow.agent.executor=agents-flex
  --forgeflow.tools.local.enabled=true
  --forgeflow.memory.learner=llm
  --forgeflow.memory.max-facts=3
  --forgeflow.memory.decay.max-facts-per-session=2

and the mock model server (scripts/mock-openai-server.py) is reachable, because the
learner prompt is answered by the mock's MEMORY_LEARN branch.

Usage: python -I scripts/memory-lifecycle-check.py [base-url]
"""
import json
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
TENANT = "tenant-a"


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        text = response.read().decode()
        return json.loads(text) if text else None


def submit(prompt, session=None):
    body = {"tenantId": TENANT, "prompt": prompt}
    if session:
        body["sessionId"] = session
    return call("POST", "/api/tasks", body)["id"]


def run(prompt, session=None, timeout=90):
    task_id = submit(prompt, session)
    deadline = time.time() + timeout
    while time.time() < deadline:
        view = call("GET", "/api/tasks/" + task_id)
        if view["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
            return view
        time.sleep(0.4)
    raise SystemExit("task " + task_id + " did not finish")


def facts(session):
    snapshot = call("GET", "/api/sessions/" + session)
    return {fact["factKey"]: fact for fact in snapshot.get("facts") or []}


def wait_for(predicate, description, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            print("  ok   " + description)
            return
        time.sleep(1.0)
    raise SystemExit("TIMEOUT waiting for " + description)


def main():
    report = {}

    print("[1] conflicting facts: latest wins, history keeps the old value")
    session = "lifecycle-conflict"
    for prompt in ("MEMORY-TURN MYNAME Alice PROJECT ForgeFlow",
                   "MEMORY-TURN CITY Beijing",
                   "MEMORY-TURN CITY Shanghai"):
        view = run(prompt, session)
        assert view["status"] == "SUCCEEDED", view
    wait_for(lambda: facts(session).get("city", {}).get("factValue") == "Shanghai",
             "city = Shanghai")
    city = facts(session)["city"]
    report["conflict"] = {"value": city["factValue"], "version": city["version"],
                          "importance": city["importance"]}
    history = call("GET", "/api/sessions/" + session + "/facts/city/history")
    assert history and history[0]["kind"] == "CONFLICT", history
    assert history[0]["oldValue"] == "Beijing", history
    report["conflict"]["history"] = [{"kind": h["kind"], "old": h["oldValue"],
                                      "new": h["newValue"], "sourceTaskId":
                                      h["sourceTaskId"]} for h in history]
    print("  ok   history: " + json.dumps(report["conflict"]["history"], ensure_ascii=False))

    print("[2] similarity recall + reinforcement")
    session = "lifecycle-recall"
    run("MEMORY-TURN MYNAME Alice", session)
    for topic in ("alpha", "beta", "gamma", "delta"):
        run("MEMORY-TURN TOPIC " + topic, session)
    before = facts(session)
    assert "user_name" in before, before
    run("what is my name? MEMORY-TURN nothing", session)
    time.sleep(2)
    after = facts(session)
    grew = [key for key in after
            if after[key]["hits"] > before.get(key, {}).get("hits", 0)]
    assert "user_name" in grew, (before, after)
    assert len(after) > len(grew), after
    report["recall"] = {"hits_grew": grew,
                        "hits_unchanged": [k for k in after if k not in grew],
                        "user_name_hits": after["user_name"]["hits"]}
    print("  ok   reinforced: " + ", ".join(grew))

    print("[3] decay: each session keeps at most max-facts-per-session active facts")
    session = "lifecycle-decay"
    for topic in ("one", "two", "three", "four"):
        run("MEMORY-TURN TOPIC " + topic, session)
    wait_for(lambda: len(facts(session)) <= 2,
             "active facts capped at 2 (scanner run)", timeout=150)
    report["decay"] = {"active": sorted(facts(session))}

    print("[4] container sandbox for the dangerous tool")
    view = run("please DO something DANGEROUS")
    assert view["status"] == "SUCCEEDED", view
    report["sandbox"] = {"task": view["id"], "status": view["status"],
                         "result": (view.get("result") or "")[:120]}
    print("  ok   " + (view.get("result") or "")[:120])

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
