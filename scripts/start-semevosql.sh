#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/deploy/semevosql/docker-compose.yml"
COMPOSE_ENV_FILE="${SEMEVOSQL_COMPOSE_ENV_FILE:-$PROJECT_ROOT/deploy/semevosql/.env}"
STARTUP_TIMEOUT="${SEMEVOSQL_STARTUP_TIMEOUT:-180}"

if [[ ! -f "$COMPOSE_ENV_FILE" ]]; then
  echo "SemEvoSQL deployment environment is missing: $COMPOSE_ENV_FILE" >&2
  echo "Run ./scripts/init-deployment-env.sh to create it." >&2
  exit 1
fi

# Upgrade compatibility for deployment files created before the SEMEVOSQL_* namespace
# was standardized. Values are read as data, never executed as shell, and existing
# SEMEVOSQL_* values from either the process environment or the env file always win.
env_file_has_key() {
  local key="$1"
  awk -v key="$key" 'index($0, key "=") == 1 { found = 1 } END { exit found ? 0 : 1 }' "$COMPOSE_ENV_FILE"
}

env_file_value() {
  local key="$1"
  awk -v key="$key" 'index($0, key "=") == 1 { value = substr($0, length(key) + 2) } END { print value }' "$COMPOSE_ENV_FILE"
}

export_legacy_alias() {
  local current="$1"
  local legacy="$2"
  local value
  if [[ -n "${!current:-}" ]] || env_file_has_key "$current"; then
    return
  fi
  value="$(env_file_value "$legacy")"
  if [[ ${#value} -ge 2 && "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
    value="${value:1:${#value}-2}"
  elif [[ ${#value} -ge 2 && "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
    value="${value:1:${#value}-2}"
  fi
  if [[ -n "$value" ]]; then
    printf -v "$current" '%s' "$value"
    export "$current"
  fi
}

export_legacy_alias SEMEVOSQL_BACKEND_PORT QW_BACKEND_PORT
export_legacy_alias SEMEVOSQL_FRONTEND_PORT QW_FRONTEND_PORT
export_legacy_alias SEMEVOSQL_METADATA_DATABASE QW_METADATA_DATABASE
export_legacy_alias SEMEVOSQL_METADATA_PASSWORD QW_METADATA_PASSWORD
export_legacy_alias SEMEVOSQL_METADATA_USER QW_METADATA_USER
export_legacy_alias SEMEVOSQL_DEMO_MYSQL_ROOT_PASSWORD QW_SOURCE_ROOT_PASSWORD
export_legacy_alias SEMEVOSQL_DEMO_MYSQL_READER_PASSWORD QW_READER_PASSWORD
export_legacy_alias SEMEVOSQL_DEMO_MYSQL_PORT QW_SOURCE_MYSQL_PORT
export_legacy_alias SEMEVOSQL_DEMO_POSTGRES_ADMIN QW_POSTGRES_ADMIN
export_legacy_alias SEMEVOSQL_DEMO_POSTGRES_ADMIN_PASSWORD QW_POSTGRES_ADMIN_PASSWORD
export_legacy_alias SEMEVOSQL_DEMO_POSTGRES_READER_PASSWORD QW_POSTGRES_READER_PASSWORD
export_legacy_alias SEMEVOSQL_DEMO_POSTGRES_PORT QW_SOURCE_POSTGRES_PORT
export_legacy_alias SEMEVOSQL_EXECUTION_INTERNAL_TOKEN QUERYWEAVER_EXECUTION_INTERNAL_TOKEN
export_legacy_alias SEMEVOSQL_SECRET_ENCRYPTION_KEY QUERYWEAVER_SECRET_ENCRYPTION_KEY
export_legacy_alias SEMEVOSQL_JWT_ISSUER_URI QUERYWEAVER_JWT_ISSUER_URI
export_legacy_alias SEMEVOSQL_SPRING_PROFILE QUERYWEAVER_SPRING_PROFILE
export_legacy_alias SEMEVOSQL_SECURITY_ENABLED QUERYWEAVER_SECURITY_ENABLED
export_legacy_alias SEMEVOSQL_OPERATOR_DEVELOPMENT_MODE QUERYWEAVER_OPERATOR_DEVELOPMENT_MODE

# Existing QueryWeaver developer environments keep their Docker project, volumes and network unless
# explicitly migrated. Public SemEvoSQL deployments use the new defaults from .env.example.
LEGACY_COMPOSE_FILE=""
if env_file_has_key "QUERYWEAVER_EXECUTION_INTERNAL_TOKEN" && ! env_file_has_key "SEMEVOSQL_COMPOSE_PROJECT_NAME"; then
  export SEMEVOSQL_COMPOSE_PROJECT_NAME="${SEMEVOSQL_COMPOSE_PROJECT_NAME:-queryweaver}"
  export SEMEVOSQL_METADATA_VOLUME="${SEMEVOSQL_METADATA_VOLUME:-queryweaver-metadata-pg16}"
  export SEMEVOSQL_UPLOADS_VOLUME="${SEMEVOSQL_UPLOADS_VOLUME:-queryweaver-uploads}"
  export SEMEVOSQL_NETWORK_NAME="${SEMEVOSQL_NETWORK_NAME:-queryweaver-net}"
  LEGACY_COMPOSE_FILE="$PROJECT_ROOT/deploy/semevosql/docker-compose.legacy.yml"
fi

compose=(docker compose --env-file "$COMPOSE_ENV_FILE" -f "$COMPOSE_FILE")
if [[ -n "$LEGACY_COMPOSE_FILE" ]]; then
  compose+=(-f "$LEGACY_COMPOSE_FILE")
fi
compose+=(--profile app)
"${compose[@]}" config --quiet

up_args=(up -d)
if [[ "${SEMEVOSQL_BUILD:-true}" == "true" ]]; then
  up_args+=(--build)
elif [[ "${SEMEVOSQL_BUILD:-true}" != "false" ]]; then
  echo "SEMEVOSQL_BUILD must be true or false" >&2
  exit 1
fi
"${compose[@]}" "${up_args[@]}"

backend_binding="$("${compose[@]}" port backend 8065 | head -n 1)"
frontend_binding="$("${compose[@]}" port frontend 8080 | head -n 1)"
BACKEND_PORT="${backend_binding##*:}"
FRONTEND_PORT="${frontend_binding##*:}"
if [[ -z "$BACKEND_PORT" || -z "$FRONTEND_PORT" ]]; then
  echo "Unable to resolve published backend/frontend ports from Docker Compose." >&2
  exit 1
fi

wait_for_url() {
  local name="$1"
  local url="$2"
  for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
    if curl -fsS --connect-timeout 2 --max-time 5 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "$name readiness timeout: $url" >&2
  return 1
}

wait_for_url "Backend" "http://127.0.0.1:$BACKEND_PORT/actuator/health"
wait_for_url "Frontend" "http://127.0.0.1:$FRONTEND_PORT/semevosql/"

cat <<EOF
SemEvoSQL is ready.
Web Console: http://127.0.0.1:$FRONTEND_PORT/semevosql/
Backend:     http://127.0.0.1:$BACKEND_PORT

Model services are external dependencies. Configure and validate Chat, Embedding and Rerank APIs in the Web Console.
EOF
