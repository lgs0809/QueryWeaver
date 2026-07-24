#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0

fail_if_matches() {
  local description="$1"
  local pattern="$2"
  shift 2
  local output
  if output="$(git grep -n -E "$pattern" -- "$@" ':!scripts/check-release-hygiene.sh' 2>/dev/null)"; then
    echo "[release-hygiene] $description" >&2
    echo "$output" >&2
    failures=1
  fi
}

# All production/test Java code belongs to the public QueryWeaver namespace.
foreign_packages="$(git grep -n -E '^package ' -- backend/src 2>/dev/null | grep -v ':package cn\.lgs\.queryweaver' || true)"
if [[ -n "$foreign_packages" ]]; then
  echo "[release-hygiene] Java package outside cn.lgs.queryweaver" >&2
  echo "$foreign_packages" >&2
  failures=1
fi

if ! grep -q '<groupId>cn.lgs.queryweaver</groupId>' pom.xml; then
  echo "[release-hygiene] parent Maven groupId must remain cn.lgs.queryweaver" >&2
  failures=1
fi

fail_if_matches \
  "machine-specific absolute path" \
  '(/Users/[^[:space:]]+|C:\\Users\\[^[:space:]]+|/home/[^[:space:]]+)' \
  . ':!backend/target/**' ':!target/**'

fail_if_matches \
  "retired QW_* environment prefix outside the compatibility shim" \
  '(^|[^A-Za-z0-9_])QW_[A-Z0-9_]+' \
  . ':!scripts/start-queryweaver.sh'

fail_if_matches \
  "private key material" \
  'BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY' \
  .

fail_if_matches \
  "credential-shaped sk-* token" \
  '(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}' \
  .

if [[ "$failures" -ne 0 ]]; then
  exit 1
fi

echo "Release hygiene checks passed."
