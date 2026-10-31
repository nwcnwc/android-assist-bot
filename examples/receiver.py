#!/usr/bin/env python3
"""Print an Ask Button JSON payload. Optionally forward the text to the xAI API.

Ask Button only posts to https URLs. Put this behind TLS (Caddy, ngrok, cloudflared)
or point the phone at a Grok Bot routine webhook instead.

    python3 examples/receiver.py
    # listens on 127.0.0.1:8787
"""

from __future__ import annotations

import base64
import hashlib
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


def _int_field(text: str, name: str) -> int | None:
    match = re.search(rf'"{name}"\s*:\s*(\d+)', text)
    if not match:
        return None
    return int(match.group(1))


def _str_field(text: str, name: str) -> tuple[bool, str | None]:
    match = re.search(rf'"{name}"\s*:\s*(null|"([^"\\]*)")', text)
    if not match:
        return False, None
    if match.group(1) == "null":
        return True, None
    return True, match.group(2)


def recover_truncated(text: str) -> dict:
    payload: dict = {"_recovered": True}
    found = False
    match = re.search(r'"probe"\s*:\s*(true|false)', text)
    if match:
        payload["probe"] = match.group(1) == "true"
        found = True
    for name in ("probe_step", "probe_total", "screenshot_b64_len", "body_bytes"):
        value = _int_field(text, name)
        if value is not None:
            payload[name] = value
            found = True
    for name in ("screenshot_b64_sha256", "probe_tail"):
        present, value = _str_field(text, name)
        if present:
            payload[name] = value
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


def probe_check(payload: dict) -> tuple[dict, str] | None:
    """Compare a size-probe post with the bytes this process actually read."""
    if payload.get("probe") is not True:
        return None
    shot = payload.get("screenshot_base64")
    received = shot if isinstance(shot, str) else ""
    declared = payload.get("screenshot_b64_len")
    tail = payload.get("probe_tail")
    declared_sha = payload.get("screenshot_b64_sha256")
    tail_present = "probe_tail" in payload
    sha_present = "screenshot_b64_sha256" in payload
    if received:
        tail_match = tail_present and tail == received[-16:]
        actual_sha = hashlib.sha256(received.encode("utf-8")).hexdigest()
        sha_match = sha_present and isinstance(declared_sha, str) and declared_sha.lower() == actual_sha
        try:
            base64.b64decode(received, validate=True)
            decodes = True
        except Exception:
            decodes = False
    else:
        tail_match = tail_present and tail is None
        sha_match = sha_present and declared_sha is None
        decodes = False
    step = payload.get("probe_step")
    total = payload.get("probe_total")
    received_len = len(received)
    fields = {
        "probe_step": step,
        "probe_total": total,
        "received_len": received_len,
        "tail_match": tail_match,
        "sha256_match": sha_match,
        "base64_decodes": decodes,
    }
    line = (
        f"probe step {step}/{total} declared_len={declared} received_len={received_len} "
        f"tail_match={tail_match} sha256_match={sha_match} base64_decodes={decodes}"
    )
    return fields, line


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
        probe = probe_check(payload)
        if probe is not None:
            summary.update(probe[0])
        print(json.dumps(summary, indent=2))
        if probe is not None:
            print(probe[1])
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
