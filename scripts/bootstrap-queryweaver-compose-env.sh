#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_FILE="${QUERYWEAVER_COMPOSE_ENV_FILE:-$PROJECT_ROOT/deploy/queryweaver/.env}"
METADATA_CONTAINER="${QUERYWEAVER_METADATA_CONTAINER:-queryweaver-metadata-db}"
SOURCE_MYSQL_CONTAINER="${QUERYWEAVER_SOURCE_MYSQL_CONTAINER:-queryweaver-readonly-mysql}"
SOURCE_POSTGRES_CONTAINER="${QUERYWEAVER_SOURCE_POSTGRES_CONTAINER:-queryweaver-readonly-postgres}"
BACKEND_CONTAINER="${QUERYWEAVER_BACKEND_CONTAINER:-queryweaver-backend}"

if [[ -f "$OUTPUT_FILE" ]]; then
  if [[ "${QUERYWEAVER_LOCAL_MODE:-false}" == "true" ]] \
    && ! grep -q '^QUERYWEAVER_SPRING_PROFILE=' "$OUTPUT_FILE"; then
    {
      printf 'QUERYWEAVER_SPRING_PROFILE=local\n'
      printf 'QUERYWEAVER_SECURITY_ENABLED=false\n'
      printf 'QUERYWEAVER_OPERATOR_DEVELOPMENT_MODE=true\n'
    } >> "$OUTPUT_FILE"
    echo "Enabled local acceptance profile in ignored Compose environment: $OUTPUT_FILE"
  fi
  echo "QueryWeaver Compose environment already exists: $OUTPUT_FILE"
  exit 0
fi

required_containers=(
  "$METADATA_CONTAINER"
  "$SOURCE_MYSQL_CONTAINER"
  "$SOURCE_POSTGRES_CONTAINER"
  "$BACKEND_CONTAINER"
)
for container_name in "${required_containers[@]}"; do
  docker inspect "$container_name" >/dev/null 2>&1 || {
    echo "Cannot bootstrap Compose environment; container is missing: $container_name" >&2
    echo "Copy deploy/queryweaver/.env.example to .env and configure a new installation manually." >&2
    exit 1
  }
done

container_env() {
  local container_name="$1"
  local key="$2"
  docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$container_name" \
    | awk -F= -v expected="$key" '$1 == expected {sub(/^[^=]*=/, ""); print; exit}'
}

require_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "Cannot bootstrap Compose environment; existing container has no value for $name" >&2
    exit 1
  fi
}

metadata_password="$(container_env "$METADATA_CONTAINER" MYSQL_PASSWORD)"
metadata_root_password="$(container_env "$METADATA_CONTAINER" MYSQL_ROOT_PASSWORD)"
source_root_password="$(container_env "$SOURCE_MYSQL_CONTAINER" MYSQL_ROOT_PASSWORD)"
reader_password="$(container_env "$SOURCE_MYSQL_CONTAINER" QW_READER_PASSWORD)"
postgres_admin="$(container_env "$SOURCE_POSTGRES_CONTAINER" POSTGRES_USER)"
postgres_admin_password="$(container_env "$SOURCE_POSTGRES_CONTAINER" POSTGRES_PASSWORD)"
postgres_reader_password="$(container_env "$SOURCE_POSTGRES_CONTAINER" QW_POSTGRES_READER_PASSWORD)"
secret_encryption_key="${QUERYWEAVER_SECRET_ENCRYPTION_KEY:-$(container_env "$BACKEND_CONTAINER" QUERYWEAVER_SECRET_ENCRYPTION_KEY)}"
jwt_issuer_uri="$(container_env "$BACKEND_CONTAINER" QUERYWEAVER_JWT_ISSUER_URI)"
execution_token="$(container_env "$BACKEND_CONTAINER" QUERYWEAVER_EXECUTION_INTERNAL_TOKEN)"

require_value QW_METADATA_PASSWORD "$metadata_password"
require_value QW_METADATA_ROOT_PASSWORD "$metadata_root_password"
require_value QW_SOURCE_ROOT_PASSWORD "$source_root_password"
require_value QW_READER_PASSWORD "$reader_password"
require_value QW_POSTGRES_ADMIN_PASSWORD "$postgres_admin_password"
require_value QW_POSTGRES_READER_PASSWORD "$postgres_reader_password"

if [[ -z "$secret_encryption_key" ]]; then
  if [[ "${QUERYWEAVER_ALLOW_NEW_ENCRYPTION_KEY:-false}" != "true" ]]; then
    echo "Cannot bootstrap Compose environment; existing container has no encryption key." >&2
    echo "For a verified plaintext-only first migration, rerun with QUERYWEAVER_ALLOW_NEW_ENCRYPTION_KEY=true." >&2
    exit 1
  fi
  secret_encryption_key="$(openssl rand -base64 32)"
fi

if (( ${#execution_token} < 32 )); then
  execution_token="$(openssl rand -hex 32)"
fi

umask 077
{
  printf 'QW_METADATA_PORT=%s\n' "${QW_METADATA_PORT:-33306}"
  printf 'QW_SOURCE_MYSQL_PORT=%s\n' "${QW_SOURCE_MYSQL_PORT:-33307}"
  printf 'QW_SOURCE_POSTGRES_PORT=%s\n' "${QW_SOURCE_POSTGRES_PORT:-35432}"
  printf 'QW_BACKEND_PORT=%s\n' "${QW_BACKEND_PORT:-28065}"
  printf 'QW_FRONTEND_PORT=%s\n' "${QW_FRONTEND_PORT:-23000}"
  printf 'QW_METADATA_DATABASE=%s\n' "${QW_METADATA_DATABASE:-queryweaver}"
  printf 'QW_METADATA_USER=%s\n' "${QW_METADATA_USER:-queryweaver}"
  printf 'QW_METADATA_PASSWORD=%s\n' "$metadata_password"
  printf 'QW_METADATA_ROOT_PASSWORD=%s\n' "$metadata_root_password"
  printf 'QW_SOURCE_ROOT_PASSWORD=%s\n' "$source_root_password"
  printf 'QW_READER_PASSWORD=%s\n' "$reader_password"
  printf 'QW_POSTGRES_ADMIN=%s\n' "${postgres_admin:-queryweaver_admin}"
  printf 'QW_POSTGRES_ADMIN_PASSWORD=%s\n' "$postgres_admin_password"
  printf 'QW_POSTGRES_READER_PASSWORD=%s\n' "$postgres_reader_password"
  printf 'QUERYWEAVER_EXECUTION_INTERNAL_TOKEN=%s\n' "$execution_token"
	printf 'QUERYWEAVER_SECRET_ENCRYPTION_KEY=%s\n' "$secret_encryption_key"
	printf 'QUERYWEAVER_JWT_ISSUER_URI=%s\n' "$jwt_issuer_uri"
	if [[ "${QUERYWEAVER_LOCAL_MODE:-false}" == "true" ]]; then
		printf 'QUERYWEAVER_SPRING_PROFILE=local\n'
		printf 'QUERYWEAVER_SECURITY_ENABLED=false\n'
		printf 'QUERYWEAVER_OPERATOR_DEVELOPMENT_MODE=true\n'
	else
		printf 'QUERYWEAVER_SPRING_PROFILE=prod\n'
		printf 'QUERYWEAVER_SECURITY_ENABLED=true\n'
		printf 'QUERYWEAVER_OPERATOR_DEVELOPMENT_MODE=false\n'
	fi
} > "$OUTPUT_FILE"

echo "Bootstrapped ignored QueryWeaver Compose environment: $OUTPUT_FILE"
