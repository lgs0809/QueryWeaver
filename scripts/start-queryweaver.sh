#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/deploy/queryweaver/docker-compose.yml"
COMPOSE_ENV_FILE="${QUERYWEAVER_COMPOSE_ENV_FILE:-$PROJECT_ROOT/deploy/queryweaver/.env}"
MODEL_ENV_FILE="${MODEL_ENV_FILE:-$PROJECT_ROOT/.env.local}"
METADATA_CONTAINER="${QUERYWEAVER_METADATA_CONTAINER:-queryweaver-metadata-db}"
BACKEND_CONTAINER="${QUERYWEAVER_BACKEND_CONTAINER:-queryweaver-backend}"
WORKER_CONTAINER="${QUERYWEAVER_WORKER_CONTAINER:-queryweaver-execution-worker}"
FRONTEND_CONTAINER="${QUERYWEAVER_FRONTEND_CONTAINER:-queryweaver-frontend}"
BACKEND_PORT="${QW_BACKEND_PORT:-28065}"
STARTUP_TIMEOUT="${QUERYWEAVER_STARTUP_TIMEOUT:-180}"

if [[ ! -f "$MODEL_ENV_FILE" ]]; then
  echo "Model environment file is missing: $MODEL_ENV_FILE" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "$MODEL_ENV_FILE"
set +a

manage_embedding_model="${QUERYWEAVER_MANAGE_EMBEDDING_MODEL:-auto}"
configured_embedding_url="${QUERYWEAVER_EMBEDDING_BASE_URL:-${OPENAI_EMBEDDING_BASE_URL:-${EMBEDDING_MODEL_BASE_URL:-}}}"
if [[ "$manage_embedding_model" == "auto" ]]; then
  case "$configured_embedding_url" in
    ""|http://127.0.0.1:*|http://localhost:*|http://host.docker.internal:*) manage_embedding_model=true ;;
    *) manage_embedding_model=false ;;
  esac
fi
if [[ "$manage_embedding_model" == "true" ]]; then
  bash "$SCRIPT_DIR/ensure-embedding-model.sh"
elif [[ "$manage_embedding_model" != "false" ]]; then
  echo "QUERYWEAVER_MANAGE_EMBEDDING_MODEL must be true, false, or auto" >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_ENV_FILE" ]] \
  && docker inspect "$BACKEND_CONTAINER" >/dev/null 2>&1; then
  QUERYWEAVER_COMPOSE_ENV_FILE="$COMPOSE_ENV_FILE" \
    bash "$SCRIPT_DIR/bootstrap-queryweaver-compose-env.sh"
fi

if [[ "${QUERYWEAVER_BUILD:-false}" == "true" ]]; then
  if [[ ! -f "$COMPOSE_ENV_FILE" ]]; then
    echo "Cannot build QueryWeaver without Compose environment: $COMPOSE_ENV_FILE" >&2
    exit 1
  fi
  maven_goals=(package)
  if [[ "${QUERYWEAVER_CLEAN_BUILD:-true}" == "true" ]]; then
    maven_goals=(clean package)
  fi
  "$PROJECT_ROOT/mvnw" -f "$PROJECT_ROOT/pom.xml" -pl backend -am -Dmaven.test.skip=true "${maven_goals[@]}"

  backend_jar="$PROJECT_ROOT/backend/target/queryweaver.jar"
  jar_bin=""
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jar" ]]; then
    jar_bin="$JAVA_HOME/bin/jar"
  else
    jar_bin="$(command -v jar || true)"
  fi
  if [[ ! -f "$backend_jar" || -z "$jar_bin" ]]; then
    echo "QueryWeaver backend artifact is missing or jar tool is unavailable: $backend_jar" >&2
    exit 1
  fi
  jar_entries="$(mktemp)"
  trap 'rm -f "$jar_entries"' EXIT
  "$jar_bin" tf "$backend_jar" >"$jar_entries"
  required_entries=(
    BOOT-INF/classes/cn/lgs/queryweaver/QueryWeaverApplication.class
    BOOT-INF/classes/cn/lgs/queryweaver/worker/CodeExecutionWorkerApplication.class
  )
  for required_entry in "${required_entries[@]}"; do
    if ! grep -Fxq "$required_entry" "$jar_entries"; then
      echo "QueryWeaver backend artifact is incomplete; missing $required_entry" >&2
      exit 1
    fi
  done
  rm -f "$jar_entries"
  trap - EXIT

  npm --prefix "$PROJECT_ROOT/frontend" run build
fi

if [[ -f "$COMPOSE_ENV_FILE" ]]; then
  compose_args=(docker compose --env-file "$COMPOSE_ENV_FILE" -f "$COMPOSE_FILE")
  compose_args+=(--profile app)
  if [[ "${QUERYWEAVER_START_WORKER:-true}" == "true" ]]; then
    compose_args+=(--profile worker)
  fi
  compose_args+=(up -d)
  if [[ "${QUERYWEAVER_BUILD:-false}" == "true" ]]; then
    compose_args+=(--build)
  fi
  "${compose_args[@]}"
else
  existing_containers=(
    "$METADATA_CONTAINER"
    "$BACKEND_CONTAINER"
    "$FRONTEND_CONTAINER"
  )
  for container_name in "${existing_containers[@]}"; do
    if ! docker inspect "$container_name" >/dev/null 2>&1; then
      echo "Compose environment file is missing: $COMPOSE_ENV_FILE" >&2
      echo "Copy deploy/queryweaver/.env.example to .env and set database passwords." >&2
      exit 1
    fi
  done
  docker start "${existing_containers[@]}" >/dev/null
  if [[ "${QUERYWEAVER_START_WORKER:-true}" == "true" ]] \
    && docker inspect "$WORKER_CONTAINER" >/dev/null 2>&1; then
    docker start "$WORKER_CONTAINER" >/dev/null
  fi
fi

wait_for_backend() {
  for ((i = 0; i < STARTUP_TIMEOUT; i++)); do
    if curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$BACKEND_PORT/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "QueryWeaver backend readiness timeout" >&2
  docker logs --tail 100 "$BACKEND_CONTAINER" >&2
  return 1
}

wait_for_backend
seed_embedding_base_url="${QUERYWEAVER_EMBEDDING_BASE_URL:-${OPENAI_EMBEDDING_BASE_URL:-${EMBEDDING_MODEL_BASE_URL:-http://127.0.0.1:${EMBEDDING_MODEL_HOST_PORT:-8110}}}}"
seed_embedding_base_url="${seed_embedding_base_url/127.0.0.1/host.docker.internal}"
seed_embedding_base_url="${seed_embedding_base_url/localhost/host.docker.internal}"
QUERYWEAVER_SEED_EMBEDDING_BASE_URL="$seed_embedding_base_url" \
  MODEL_ENV_FILE="$MODEL_ENV_FILE" bash "$SCRIPT_DIR/seed-model-config.sh"

docker restart "$BACKEND_CONTAINER" >/dev/null
if [[ "${QUERYWEAVER_START_WORKER:-true}" == "true" ]] \
  && docker inspect "$WORKER_CONTAINER" >/dev/null 2>&1; then
  docker restart "$WORKER_CONTAINER" >/dev/null
fi

wait_for_backend
model_readiness="$(
  curl -fsS --connect-timeout 2 --max-time 10 \
    "http://127.0.0.1:$BACKEND_PORT/api/model-config/check-ready"
)"
if [[ "$model_readiness" != *'"ready":true'* ]]; then
  echo "QueryWeaver model configuration is not ready" >&2
  exit 1
fi

echo "QueryWeaver is ready: http://127.0.0.1:${QW_FRONTEND_PORT:-23000}/queryweaver"
