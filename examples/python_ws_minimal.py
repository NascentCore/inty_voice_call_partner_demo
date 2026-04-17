#!/usr/bin/env python3
"""最小 WebSocket 握手示例：仅验证连接与首条下行（需与 status 中 send_sample_rate 一致后再发 audio）。"""

import asyncio
import json
import os
import ssl

import websockets

WSS_BASE = os.environ.get("INTY_WSS_BASE", "wss://YOUR_INTY_HOST").rstrip("/")
AGENT_ID = os.environ.get("INTY_AGENT_ID", "YOUR_AGENT_ID")
TOKEN = os.environ.get("INTY_TOKEN", "")


async def main() -> None:
    if not TOKEN:
        raise SystemExit("Set INTY_TOKEN to your JWT")
    uri = f"{WSS_BASE}/api/v1/live-chat/{AGENT_ID}"
    # websockets 16+ 使用 additional_headers；旧版 asyncio API 曾用 extra_headers。
    headers = {"Authorization": f"Bearer {TOKEN}"}
    ssl_ctx = ssl.create_default_context()
    async with websockets.connect(uri, additional_headers=headers, ssl=ssl_ctx) as ws:
        raw = await ws.recv()
        msg = json.loads(raw)
        print(json.dumps(msg, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
