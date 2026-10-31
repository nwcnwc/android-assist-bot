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
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen


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
        try:
            payload = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            payload = {"_raw": raw.decode("utf-8", "replace")}
        if not isinstance(payload, dict):
            payload = {"_raw": raw.decode("utf-8", "replace")}
        summary = {
            key: payload.get(key)
            for key in (
                "question",
                "url",
                "url_source",
                "screen_text",
                "screenshot_mime",
                "screenshot_omitted",
            )
        }
        shot = payload.get("screenshot_base64")
        summary["screenshot_base64_chars"] = len(shot) if isinstance(shot, str) else 0
        print(json.dumps(summary, indent=2))
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
