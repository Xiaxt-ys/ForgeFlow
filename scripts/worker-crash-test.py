#!/usr/bin/env python3
"""Kill/restart the app N times and measure task recovery time.

Prereqs: Docker MySQL/Redis/RabbitMQ are running (docker compose up -d),
and the bootstrap jar is built.

Example:
  python scripts/worker-crash-test.py --cycles 3
  python scripts/worker-crash-test.py --cycles 20
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar")


def http_json(url, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode())


def wait_health(base, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            http_json(base + "/actuator/health")
            return
        except Exception:
            time.sleep(0.5)
    raise TimeoutError("app did not become healthy")


def start_app(port, task_delay_ms):
    return subprocess.Popen(
        ["java", "-jar", JAR,
         "--spring.profiles.active=prod",
         f"--server.port={port}",
         "--forgeflow.agent.executor=echo",
         f"--forgeflow.agent.echo.delay-ms={task_delay_ms}",
         "--forgeflow.tenant.max-inflight=0"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
    )


def stop_app(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cycles", type=int, default=20)
    parser.add_argument("--port", type=int, default=18089)
    parser.add_argument("--startup-timeout", type=int, default=60)
    parser.add_argument("--recovery-timeout", type=int, default=120)
    parser.add_argument("--task-delay-ms", type=int, default=5000)
    args = parser.parse_args()

    if not os.path.exists(JAR):
        sys.exit(f"jar not found: {JAR}\nrun: mvn -DskipTests package")

    base = f"http://localhost:{args.port}"
    recoveries = []
    process = start_app(args.port, args.task_delay_ms)
    try:
        wait_health(base, args.startup_timeout)
        for cycle in range(1, args.cycles + 1):
            response = http_json(base + "/api/tasks", "POST", {
                "tenantId": "tenant-a",
                "prompt": f"crash-test-{cycle}",
            })
            task_id = response["id"]
            # Wait until the worker actually starts executing.
            deadline = time.time() + 20
            while time.time() < deadline:
                status = http_json(base + f"/api/tasks/{task_id}")["status"]
                if status in ("RUNNING", "CLAIMED"):
                    break
                time.sleep(0.2)

            killed_at = time.time()
            stop_app(process)
            process = start_app(args.port, args.task_delay_ms)
            wait_health(base, args.startup_timeout)

            deadline = time.time() + args.recovery_timeout
            final_status = None
            while time.time() < deadline:
                final_status = http_json(base + f"/api/tasks/{task_id}")["status"]
                if final_status in ("SUCCEEDED", "FAILED", "CANCELLED"):
                    break
                time.sleep(0.5)
            elapsed = time.time() - killed_at
            recoveries.append(elapsed)
            print(f"cycle={cycle} task={task_id} status={final_status} "
                  f"recovery={elapsed:.1f}s")
    finally:
        stop_app(process)

    if recoveries:
        print(f"cycles={len(recoveries)} max={max(recoveries):.1f}s "
              f"avg={sum(recoveries) / len(recoveries):.1f}s")


if __name__ == "__main__":
    main()
