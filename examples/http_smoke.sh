#!/usr/bin/env bash
set -euo pipefail

# 使用前请设置环境变量或使用下方默认值占位符。
INTY_BASE="${INTY_BASE:-https://YOUR_INTY_HOST}"
TOKEN="${TOKEN:-YOUR_JWT_HERE}"

if [[ "$TOKEN" == "YOUR_JWT_HERE" ]]; then
  echo "Set TOKEN (and optionally INTY_BASE) before running." >&2
  exit 1
fi

curl -sS -H "Authorization: Bearer ${TOKEN}" \
  "${INTY_BASE%/}/api/v1/live-chat/status" | jq .
