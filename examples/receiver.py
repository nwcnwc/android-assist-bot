#!/usr/bin/env python3
"""Print an Ask Button JSON payload. Optionally forward the text to the xAI API.

Ask Button only posts to https URLs. Put this behind TLS (Caddy, ngrok, cloudflared)
or point the phone at a Grok Bot routine webhook instead.

    python3 examples/receiver.py
    # listens on 127.0.0.1:8787
"""

from __future__ import annotations

import json
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen


def load_payload(raw: bytes) -> dict:
    text = raw.decode("utf-8", "replace")
    try:
        parsed = json.loads(text or "{}")
    except json.JSONDecodeError:
        # Size fields sit before screenshot_base64, so a cut body can still be measured.
        return recover_truncated(text)
    if isinstance(parsed, dict):
        return parsed
    return {"_raw": text}


def recover_truncated(text: str) -> dict:
    payload: dict = {"_recovered": True}
    found = False
    match = re.search(r'"screenshot_b64_len"\s*:\s*(\d+)', text)
    if match:
        payload["screenshot_b64_len"] = int(match.group(1))
        found = True
    match = re.search(r'"body_bytes"\s*:\s*(\d+)', text)
    if match:
        payload["body_bytes"] = int(match.group(1))
        found = True
    match = re.search(r'"screenshot_base64"\s*:\s*(null|"(.*))', text, re.S)
    if match:
        found = True
        if match.group(1) == "null":
            payload["screenshot_base64"] = None
        else:
            raw_shot = match.group(2)
            end = raw_shot.find('"')
            payload["screenshot_base64"] = raw_shot if end < 0 else raw_shot[:end]
    if not found:
        return {"_raw": text}
    return payload


def shot_len(payload: dict) -> int:
    shot = payload.get("screenshot_base64")
    return len(shot) if isinstance(shot, str) else 0


def is_truncated(payload: dict) -> bool:
    declared = payload.get("screenshot_b64_len")
    return isinstance(declared, int) and declared != shot_len(payload)


def ask_llm(payload: dict) -> str | None:
    key = os.environ.get("XAI_API_KEY", "").strip()
    if not key:
        return None
    question = payload.get("question") or "What is on this screen?"
    parts = [question]
    if payload.get("url"):
        parts.append("Link: " + str(payload["url"]))
    if payload.get("screen_text"):
        parts.append("On-screen text:\n" + str(payload["screen_text"]))
    shot = payload.get("screenshot_base64")
    if isinstance(shot, str) and shot:
        parts.append(
            "A JPEG screenshot is included ("
            + str(payload.get("screenshot_mime") or "image/jpeg")
            + ", "
            + str(len(shot))
            + " base64 characters)."
        )
    elif payload.get("screenshot_omitted"):
        parts.append("Screenshot omitted: " + str(payload["screenshot_omitted"]))
    body = json.dumps(
        {
            "model": os.environ.get("XAI_MODEL", "grok-4"),
            "messages": [{"role": "user", "content": "\n\n".join(parts)}],
        }
    ).encode()
    request = Request(
        "https://api.x.ai/v1/chat/completions",
        data=body,
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=60) as response:
        data = json.loads(response.read().decode())
    return data["choices"][0]["message"]["content"]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        payload = load_payload(raw)
        summary = {
            key: payload.get(key)
            for key in (
                "question",
                "url",
                "url_source",
                "screen_text",
                "screenshot_mime",
                "screenshot_omitted",
                "screenshot_b64_len",
                "body_bytes",
            )
        }
        summary["screenshot_base64_chars"] = shot_len(payload)
        truncated = is_truncated(payload)
        summary["truncated"] = truncated
        print(json.dumps(summary, indent=2))
        if truncated:
            print("truncated: len(screenshot_base64) != screenshot_b64_len")
        sys.stdout.flush()
        answer = None
        try:
            answer = ask_llm(payload if isinstance(payload, dict) else {})
        except Exception as error:  # noqa: BLE001 - example server, print and continue
            print("llm error:", error, file=sys.stderr)
        if answer:
            print("answer:", answer)
            sys.stdout.flush()
        body = json.dumps({"ok": True}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        print("http", self.address_string(), fmt % args)


def main() -> None:
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8787"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"listening on http://{host}:{port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
