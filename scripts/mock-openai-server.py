#!/usr/bin/env python3
"""Minimal OpenAI-compatible chat server for ForgeFlow's end-to-end checks.

It looks at the user prompt (and whether a tool result is already in the history):

  prompt contains LOOP       -> always ask for the same search call (loop guard should fire)
  prompt contains DANGEROUS  -> ask for `delete` (RBAC / sandbox check)
  prompt contains WRITE      -> ask for `write_note`
  prompt contains SEARCH     -> ask for `search`
  otherwise                  -> answer directly

After a tool result is present, it always returns a final answer, except in LOOP mode.

Usage: python -I scripts/mock-openai-server.py --port 18888
"""
import argparse
import hashlib
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def text_of(message):
    content = message.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def memory_learn(user_text):
    """Emulate the LLM memory learner: MYNAME/PROJECT/CITY/TOPIC become durable facts."""
    facts = []
    for directive, key in (("MYNAME", "user_name"), ("PROJECT", "project"),
                           ("CITY", "city")):
        for value in re.findall(directive + r"\s+([^\s,.;]+)", user_text, re.IGNORECASE):
            facts.append({"key": key, "value": value})
    # Each TOPIC value gets its own key, so repeated topics grow the fact set instead of
    # overwriting one row.
    for value in re.findall(r"TOPIC\s+([^\s,.;]+)", user_text, re.IGNORECASE):
        facts.append({"key": "topic_" + re.sub(r"[^a-z0-9]+", "_", value.lower()),
                      "value": value})
    return json.dumps({"summary": "mock summary of the session", "state": "",
                       "facts": facts})


def decide(payload):
    messages = payload.get("messages") or []
    user_text = " ".join(text_of(m) for m in messages if m.get("role") == "user")
    all_text = " ".join(text_of(m) for m in messages)
    has_tool_result = any(m.get("role") == "tool" for m in messages)
    upper = user_text.upper()
    all_upper = all_text.upper()
    model = payload.get("model", "")

    # The memory learner may arrive as a system message or a user message.
    if "YOU MAINTAIN THE LONG-TERM MEMORY" in all_upper:
        return "MEMORY_LEARN", {"content": memory_learn(all_text)}

    # A slow primary model lets the fallback path be exercised.
    if model == "slow-model":
        time.sleep(5.0)

    if "BADJSON" in upper:
        return "BADJSON", {}
    if "SLOW" in upper:
        time.sleep(2.0)
        return None, None
    if "LOOP" in upper:
        return "search", {"q": "same-query"}
    if "HANDOFF" in upper and "[HUMAN]" not in upper:
        # Empty answer: the worker's output validator should park the task for a human.
        # A resumed task carries a "[human] ..." answer, which must take the normal path.
        return "EMPTY", {}
    if "COMPENSATE" in upper:
        if has_tool_result:
            # Unknown tool -> dangerous by default -> denied -> hard failure after a write.
            return "mystery", {}
        return "write_note", {"note": "demo"}
    if has_tool_result:
        return None, None
    if "DANGEROUS" in upper:
        return "delete", {"path": "demo.txt"}
    if "WRITE" in upper:
        return "write_note", {"note": "demo"}
    if "SEARCH" in upper:
        return "search", {"q": "demo"}
    return None, None


def completion_response(payload, tool_name, arguments):
    if tool_name == "EMPTY":
        message = {"role": "assistant", "content": ""}
        finish = "stop"
    elif tool_name == "MEMORY_LEARN":
        message = {"role": "assistant", "content": arguments["content"]}
        finish = "stop"
    elif tool_name == "BADJSON":
        # Deliberately malformed JSON: output validation should park the task for a human.
        message = {"role": "assistant", "content": "{\"a\": 1,"}
        finish = "stop"
    elif tool_name is None:
        message = {"role": "assistant", "content": "mock-final: done"}
        finish = "stop"
    else:
        message = {
            "role": "assistant",
            "content": None,
            "tool_calls": [{
                "id": "call-mock-1",
                "type": "function",
                "function": {"name": tool_name, "arguments": json.dumps(arguments)},
            }],
        }
        finish = "tool_calls"
    return {
        "id": "chatcmpl-mock",
        "object": "chat.completion",
        "created": 0,
        "model": payload.get("model", "mock"),
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
    }


EMBEDDING_DIM = 64

# A tiny "semantic" space for the mock embedding endpoint: words in the same group share a
# reserved dimension, so "who am I" matches a stored "user_name: Alice" fact while a pure
# hashing vectoriser (which drops those words as stopwords) would not.
CONCEPTS = {
    0: {"name", "alice", "called", "whoami", "myname", "姓名", "名字"},
    1: {"beijing", "shanghai", "city", "live", "where", "城市"},
    2: {"forgeflow", "project", "working", "work", "项目"},
}


def embedding_vector(text):
    vector = [0.0] * EMBEDDING_DIM
    compact = "".join(re.findall(r"[a-z0-9一-鿿]+", text.lower()))
    for index, words in CONCEPTS.items():
        if any(word in compact for word in words):
            vector[EMBEDDING_DIM - 4 + index] += 3.0
    for word in re.findall(r"[a-z0-9]+", text.lower()):
        slot = int(hashlib.md5(word.encode()).hexdigest(), 16) % (EMBEDDING_DIM - 4)
        vector[slot] += 1.0
    norm = sum(value * value for value in vector) ** 0.5
    return [value / norm for value in vector] if norm else vector


def embedding_response(payload):
    inputs = payload.get("input")
    if isinstance(inputs, str):
        inputs = [inputs]
    data = [{"object": "embedding", "index": index, "embedding": embedding_vector(text)}
            for index, text in enumerate(inputs or [])]
    return {"object": "list", "model": payload.get("model", "mock-embedding"), "data": data}


class Handler(BaseHTTPRequestHandler):
    def _send(self, data):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b"{}"
        payload = json.loads(body.decode("utf-8") or "{}")
        if self.path.rstrip("/").endswith("/embeddings"):
            self._send(json.dumps(embedding_response(payload)).encode())
            return
        tool_name, arguments = decide(payload)
        self._send(json.dumps(completion_response(payload, tool_name, arguments)).encode())

    def log_message(self, fmt, *args):
        print("mock: " + fmt % args, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18888)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"mock openai server on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
