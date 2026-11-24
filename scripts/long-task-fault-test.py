#!/usr/bin/env python3
"""Long task + fault: a 45s task is running when its worker is killed, a second worker takes
over after the lease expires, and RabbitMQ is restarted while a second task waits in the outbox.

Prerequisites:
  docker compose up -d            # MySQL + Redis + RabbitMQ
  mvn -DskipTests package

Usage:
  python -I scripts/long-task-fault-test.py
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAR = os.path.join(ROOT, "forgeflow-bootstrap/target/forgeflow-bootstrap-0.1.0-SNAPSHOT.jar")
RABBIT = "forgeflow-rabbitmq"
MYSQL = "forgeflow-mysql"
TASK_DELAY_MS = 45_000
LONG_PROMPT_CHARS = 200_000


def http_json(url, method="GET", body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode())


def wait_health(base, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            http_json(base + "/actuator/health", timeout=5)
            return
        except Exception:
            time.sleep(0.5)
    raise TimeoutError("app did not become healthy")


def wait_status(base, task_id, wanted, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        view = http_json(base + "/api/tasks/" + task_id)
        if view["status"] in wanted:
            return view
        time.sleep(0.3)
    raise TimeoutError("task " + task_id + " did not reach " + ",".join(wanted))


def start(port, worker_id, worker_enabled, delay_ms, log_name):
    args = ["java", "-Dfile.encoding=UTF-8", "-jar", JAR,
            "--spring.profiles.active=prod",
            f"--server.port={port}",
            "--forgeflow.agent.executor=echo",
            f"--forgeflow.agent.echo.delay-ms={delay_ms}",
            "--forgeflow.recovery.scan-interval-ms=2000"]
    if worker_enabled:
        args.append("--forgeflow.worker.enabled=true")
        args.append(f"--forgeflow.worker.id={worker_id}")
    else:
        args.append("--forgeflow.worker.enabled=false")
    log = open(os.path.join(tempfile.gettempdir(), log_name), "w")
    return subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)


def stop(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def mysql(query):
    result = subprocess.run(
        ["docker", "exec", MYSQL, "mysql", "-uroot", "-proot",
         "-N", "-e", query],
        capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-port", type=int, default=18200)
    parser.add_argument("--w1-port", type=int, default=18201)
    parser.add_argument("--w2-port", type=int, default=18202)
    args = parser.parse_args()

    if not os.path.exists(JAR):
        sys.exit("jar not found; run: mvn -DskipTests package")
    api = f"http://localhost:{args.api_port}"

    api_proc = worker1 = worker2 = None
    try:
        print("[0] starting API node, w2 (spare) and w1 (executor)")
        api_proc = start(args.api_port, None, False, 0, "lt-api.log")
        worker2 = start(args.w2_port, "w2", True, TASK_DELAY_MS, "lt-w2.log")
        worker1 = start(args.w1_port, "w1", True, TASK_DELAY_MS, "lt-w1.log")
        wait_health(api, 90)

        print("[1] submit long task and wait until it is RUNNING")
        task1 = http_json(api + "/api/tasks", "POST",
                          {"tenantId": "tenant-a", "prompt": "long-task-1"})["id"]
        wait_status(api, task1, {"RUNNING"}, 60)
        print("    task1 RUNNING " + task1)
        time.sleep(5)

        print("[2] kill the executing worker (w1)")
        stop(worker1)
        worker1 = None

        print("[3] stop RabbitMQ and submit a second task into the outbox")
        subprocess.run(["docker", "stop", RABBIT], check=True, capture_output=True)
        task2 = http_json(api + "/api/tasks", "POST",
                          {"tenantId": "tenant-a", "prompt": "long-task-2"})["id"]
        time.sleep(10)
        print("[4] restart RabbitMQ")
        subprocess.run(["docker", "start", RABBIT], check=True, capture_output=True)
        time.sleep(15)

        print("[5] long prompt persistence (200 KB > TEXT's 64 KB)")
        long_prompt = "L" * LONG_PROMPT_CHARS
        long_task = http_json(api + "/api/tasks", "POST",
                              {"tenantId": "tenant-a", "prompt": long_prompt})["id"]
        stored = mysql(f"SELECT CHAR_LENGTH(prompt) FROM forgeflow.ff_task WHERE id='{long_task}'")
        http_json(api + "/api/tasks/" + long_task + "/cancel", "POST")

        print("[6] waiting for the taken-over task and the outbox task")
        final1 = wait_status(api, task1, {"SUCCEEDED", "FAILED", "CANCELLED"}, 240)
        final2 = wait_status(api, task2, {"SUCCEEDED", "FAILED", "CANCELLED"}, 300)
        worker_of = mysql(f"SELECT worker_id FROM forgeflow.ff_task WHERE id='{task1}'")

        report = {
            "task1": {"status": final1["status"], "worker": worker_of,
                      "result": (final1.get("result") or "")[:40]},
            "task2": {"status": final2["status"],
                      "result": (final2.get("result") or "")[:40]},
            "long_prompt": {"chars": LONG_PROMPT_CHARS, "stored": stored},
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        assert final1["status"] == "SUCCEEDED", report
        assert final2["status"] == "SUCCEEDED", report
        assert worker_of == "w2", report
        assert stored == str(LONG_PROMPT_CHARS), report
        print("ok: takeover by w2, outbox recovered after Rabbit restart, 200KB prompt stored")
    finally:
        stop(worker1)
        stop(worker2)
        stop(api_proc)


if __name__ == "__main__":
    main()
