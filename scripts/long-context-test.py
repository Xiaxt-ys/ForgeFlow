#!/usr/bin/env python3
"""Near-window prompt test: N concurrent tasks with a ~250 KB prompt through the real agent
path (planner -> model -> output validation -> session memory write).

Run against the mock-backed H2 instance:
  python -I scripts/long-context-test.py http://localhost:18080 --count 5 --kb 250
"""
import argparse
import json
import statistics
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

INFO = "context-line-%d: ForgeFlow keeps tasks recoverable across workers.\n"


def http_json(base, method, path, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        text = response.read().decode()
        return json.loads(text) if text else None


def build_prompt(index, chars):
    head = f"LONGCTX task {index}. END-MARKER-{index}.\n"
    body = []
    length = len(head)
    line = 0
    while length < chars:
        chunk = INFO % line
        body.append(chunk)
        length += len(chunk)
        line += 1
    return head + "".join(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base", nargs="?", default="http://localhost:18080")
    parser.add_argument("--count", type=int, default=5)
    parser.add_argument("--kb", type=int, default=250)
    args = parser.parse_args()
    chars = args.kb * 1024

    def submit_and_wait(index):
        prompt = build_prompt(index, chars)
        started = time.time()
        task = http_json(args.base, "POST", "/api/tasks",
                         {"tenantId": "tenant-a", "sessionId": "long-context",
                          "prompt": prompt})
        accepted = time.time()
        deadline = time.time() + 300
        while time.time() < deadline:
            view = http_json(args.base, "GET", "/api/tasks/" + task["id"])
            if view["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
                return {
                    "id": task["id"],
                    "status": view["status"],
                    "chars": len(prompt),
                    "acceptMs": int((accepted - started) * 1000),
                    "totalMs": int((time.time() - started) * 1000),
                    "resultChars": len(view.get("result") or ""),
                }
            time.sleep(0.5)
        raise TimeoutError("task " + task["id"] + " did not finish")

    started = time.time()
    with ThreadPoolExecutor(max_workers=args.count) as pool:
        results = list(pool.map(submit_and_wait, range(args.count)))
    wall = time.time() - started

    accepts = sorted(r["acceptMs"] for r in results)
    report = {
        "count": args.count,
        "promptChars": chars,
        "wallSeconds": round(wall, 2),
        "acceptMs": {"p50": int(statistics.median(accepts)), "max": accepts[-1]},
        "results": results,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert all(r["status"] == "SUCCEEDED" for r in results), report
    session = http_json(args.base, "GET", "/api/sessions/long-context")
    print("session summary chars:", len(session.get("summary") or ""),
          "facts:", len(session.get("facts") or []))


if __name__ == "__main__":
    main()
