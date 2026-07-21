#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE="$PROJECT_ROOT/deploy/queryweaver/.env.example"
TARGET="${QUERYWEAVER_COMPOSE_ENV_FILE:-$PROJECT_ROOT/deploy/queryweaver/.env}"

if [[ ! -f "$TEMPLATE" ]]; then
  echo "Deployment template is missing: $TEMPLATE" >&2
  exit 1
fi
if [[ -e "$TARGET" ]]; then
  echo "Deployment environment already exists: $TARGET" >&2
  echo "Refusing to overwrite it." >&2
  exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required to generate deployment credentials." >&2
  exit 1
fi

metadata_value="$(openssl rand -hex 24)"
execution_value="$(openssl rand -hex 32)"
encryption_value="$(openssl rand -base64 32 | tr -d '\r\n')"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    QUERYWEAVER_METADATA_PASSWORD=)
      printf 'QUERYWEAVER_METADATA_PASSWORD=%s\n' "$metadata_value" >>"$tmp"
      ;;
    QUERYWEAVER_EXECUTION_INTERNAL_TOKEN=)
      printf 'QUERYWEAVER_EXECUTION_INTERNAL_TOKEN=%s\n' "$execution_value" >>"$tmp"
      ;;
    QUERYWEAVER_SECRET_ENCRYPTION_KEY=)
      printf 'QUERYWEAVER_SECRET_ENCRYPTION_KEY=%s\n' "$encryption_value" >>"$tmp"
      ;;
    QUERYWEAVER_MCP_PUBLIC_BASE_URL=*)
      printf 'QUERYWEAVER_MCP_PUBLIC_BASE_URL=\n' >>"$tmp"
      ;;
    QUERYWEAVER_METADATA_PORT=*)
      ;;
    *)
      printf '%s\n' "$line" >>"$tmp"
      ;;
  esac
done <"$TEMPLATE"

umask 077
mkdir -p "$(dirname "$TARGET")"
mv "$tmp" "$TARGET"
trap - EXIT
chmod 600 "$TARGET" 2>/dev/null || true

echo "Created $TARGET"
echo "Review ports/security settings if needed, then run: ./scripts/start-queryweaver.sh"
