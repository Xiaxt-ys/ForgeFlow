#!/usr/bin/env python3
"""Proves that recall uses the configured embedding backend, not the offline hash fallback.

The mock embedding endpoint maps "name/Alice/who am I" to one shared dimension, so the query
"who am I?" matches a stored `user_name: Alice` fact even though every word in it is a
stopword for the hashing vectoriser. Run the app with

  --forgeflow.memory.embedding.provider=http
  --forgeflow.memory.embedding.base-url=http://localhost:18888/v1
  --forgeflow.memory.embedding.model=mock-embedding

and compare `hits` before/after the query.
"""
import json
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080"
SESSION = "semantic-recall"


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        text = response.read().decode()
        return json.loads(text) if text else None


def run(prompt):
    task_id = call("POST", "/api/tasks",
                   {"tenantId": "tenant-a", "sessionId": SESSION, "prompt": prompt})["id"]
    for _ in range(200):
        view = call("GET", "/api/tasks/" + task_id)
        if view["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
            assert view["status"] == "SUCCEEDED", view
            return
        time.sleep(0.4)
    raise SystemExit("task did not finish")


def facts():
    snapshot = call("GET", "/api/sessions/" + SESSION)
    return {f["factKey"]: f for f in snapshot.get("facts") or []}


def main():
    run("MEMORY-TURN MYNAME Alice")
    for topic in ("alpha", "beta", "gamma", "delta"):
        run("MEMORY-TURN TOPIC " + topic)
    time.sleep(1)
    before = facts()
    assert "user_name" in before, before

    run("who am I?")

    after = facts()
    print("before:", {k: v["hits"] for k, v in before.items()})
    print("after: ", {k: v["hits"] for k, v in after.items()})
    assert after["user_name"]["hits"] > before["user_name"]["hits"], after
    print("ok: semantic query recalled user_name without sharing a single content word")


if __name__ == "__main__":
    main()
