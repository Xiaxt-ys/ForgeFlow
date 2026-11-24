#!/usr/bin/env python3
"""Submit 1000 tasks with 20 concurrent clients and report accept latency."""
import concurrent.futures
import json
import statistics
import sys
import time
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080/api/tasks"
CONCURRENCY = 20
TOTAL = 1000


def submit(index):
    payload = json.dumps({"tenantId": "tenant-a", "prompt": f"bench-{index}"}).encode()
    request = urllib.request.Request(URL, data=payload, method="POST", headers={
        "Content-Type": "application/json",
        "Idempotency-Key": f"bench-{index}-{time.time_ns()}",
    })
    start = time.perf_counter()
    with urllib.request.urlopen(request, timeout=30) as response:
        response.read()
    return (time.perf_counter() - start) * 1000


def main():
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        latencies = list(pool.map(submit, range(TOTAL)))
    elapsed = time.perf_counter() - started
    latencies.sort()

    def percentile(p):
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

    print(f"requests={TOTAL} concurrency={CONCURRENCY} elapsed={elapsed:.2f}s "
          f"throughput={TOTAL / elapsed:.0f}/s")
    print(f"p50={statistics.median(latencies):.1f}ms "
          f"p95={percentile(0.95):.1f}ms p99={percentile(0.99):.1f}ms "
          f"max={max(latencies):.1f}ms")


if __name__ == "__main__":
    main()
