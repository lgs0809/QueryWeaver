#!/usr/bin/env bash
set -euo pipefail

BIND_ADDRESS="${EMBEDDING_MODEL_BIND_ADDRESS:-127.0.0.1}"
HOST_PORT="${EMBEDDING_MODEL_HOST_PORT:-8110}"
BASE_URL="${EMBEDDING_MODEL_BASE_URL:-http://127.0.0.1:${HOST_PORT}}"
CONTAINER="${EMBEDDING_MODEL_CONTAINER:-embedding-model}"
IMAGE="${EMBEDDING_MODEL_IMAGE:-embedding-model:local}"
VOLUME="${EMBEDDING_MODEL_VOLUME:-embedding-model-cache}"
READY_TIMEOUT="${EMBEDDING_MODEL_READY_TIMEOUT:-120}"
PRELOAD_TIMEOUT="${EMBEDDING_MODEL_PRELOAD_TIMEOUT:-180}"
EMBEDDING_MODEL_ID="${EMBEDDING_MODEL_ID:-Qwen/Qwen3-VL-Embedding-2B}"
RERANK_MODEL_ID="${RERANK_MODEL_ID:-Qwen/Qwen3-VL-Reranker-2B}"
EMBEDDING_DIMENSIONS="${EMBEDDING_DIMENSIONS:-2048}"
EMBEDDING_ONLY="${EMBEDDING_MODEL_EMBEDDING_ONLY:-false}"
MODE_MARKER="QUERYWEAVER_EMBEDDING_ONLY=$EMBEDDING_ONLY"

embedding_probe() {
  curl -fsS --connect-timeout 2 --max-time 30 \
    -X POST "$BASE_URL/v1/embeddings" \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"$EMBEDDING_MODEL_ID\",\"input\":[\"QueryWeaver semantic planning retrieval readiness probe\"]}" \
    2>/dev/null \
    | python3 -c 'import json, sys; body = json.load(sys.stdin); data = body.get("data") or []; vector = data[0].get("embedding") if data else None; raise SystemExit(0 if isinstance(vector, list) and len(vector) == int(sys.argv[1]) else 1)' "$EMBEDDING_DIMENSIONS" \
      >/dev/null 2>&1
}

health_available() {
  curl -fsS --connect-timeout 2 --max-time 5 \
    "$BASE_URL/health" >/dev/null 2>&1
}

service_compatible() {
  curl -fsS --connect-timeout 2 --max-time 5 \
    "$BASE_URL/health" 2>/dev/null \
    | python3 -c 'import json, sys; body = json.load(sys.stdin); embedding, rerank, dimensions, embedding_only = sys.argv[1:]; ok = str(body.get("embeddingModel") or "") == embedding and str(body.get("embeddingDimensions") or "") == dimensions; ok = ok and (embedding_only == "true" or str(body.get("rerankModel") or "") == rerank); raise SystemExit(0 if ok else 1)' \
      "$EMBEDDING_MODEL_ID" "$RERANK_MODEL_ID" "$EMBEDDING_DIMENSIONS" "$EMBEDDING_ONLY" \
      >/dev/null 2>&1
}

embedding_ready() {
  service_compatible && embedding_probe
}

full_ready() {
  service_compatible \
    && curl -fsS --connect-timeout 2 --max-time 5 \
      "$BASE_URL/ready" >/dev/null 2>&1
}


if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  container_status="$(docker inspect -f '{{.State.Status}}' "$CONTAINER")"
  if [[ "$container_status" == "running" ]]; then
    if [[ "$EMBEDDING_ONLY" == "true" ]] && embedding_ready; then
      echo "reusing shared embedding-model (embedding capability ready)"
      exit 0
    elif [[ "$EMBEDDING_ONLY" != "true" ]] && full_ready; then
      echo "reusing shared embedding-model (embedding + reranker ready)"
      exit 0
    elif health_available && service_compatible; then
      echo "shared embedding-model is compatible and still loading; waiting for readiness"
    elif health_available; then
      echo "existing shared embedding-model is incompatible with the requested model configuration" >&2
      echo "refusing to delete a container that may be owned by another project" >&2
      exit 1
    fi
  else
    docker start "$CONTAINER" >/dev/null
  fi
fi

docker info >/dev/null 2>&1 || {
  echo "Docker daemon is unavailable" >&2
  exit 1
}

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  docker image inspect "$IMAGE" >/dev/null 2>&1 || {
    echo "Docker image is unavailable: $IMAGE" >&2
    exit 1
  }

  if [[ "$EMBEDDING_ONLY" == "true" ]]; then
    docker run -d \
      --name "$CONTAINER" \
      --restart unless-stopped \
      --health-cmd='curl -fsS http://127.0.0.1:8110/health || exit 1' \
      --health-interval=30s \
      --health-timeout=5s \
      --health-start-period=30s \
      --health-retries=5 \
      -p "$BIND_ADDRESS:$HOST_PORT:8110" \
      -v "$VOLUME:/models" \
      -e "$MODE_MARKER" \
      -e EMBEDDING_MODEL_ID="$EMBEDDING_MODEL_ID" \
      -e RERANK_MODEL_ID= \
      -e EMBEDDING_DIMENSIONS="$EMBEDDING_DIMENSIONS" \
      -e MODEL_DEVICE=auto \
      -e STARTUP_PRELOAD_MODELS=false \
      -e STARTUP_PRELOAD_BACKGROUND=false \
      -e HF_HOME=/models/huggingface \
      -e SENTENCE_TRANSFORMERS_HOME=/models/sentence-transformers \
      -e HF_HUB_DISABLE_XET=1 \
      -e HF_HUB_DISABLE_TELEMETRY=1 \
      "$IMAGE" >/dev/null
  else
    docker run -d \
      --name "$CONTAINER" \
      --restart unless-stopped \
      -p "$BIND_ADDRESS:$HOST_PORT:8110" \
      -v "$VOLUME:/models" \
      -e "$MODE_MARKER" \
      -e EMBEDDING_MODEL_ID="$EMBEDDING_MODEL_ID" \
      -e RERANK_MODEL_ID="$RERANK_MODEL_ID" \
      -e EMBEDDING_DIMENSIONS="$EMBEDDING_DIMENSIONS" \
      -e MODEL_DEVICE=auto \
      -e STARTUP_PRELOAD_MODELS=true \
      -e STARTUP_PRELOAD_BACKGROUND=true \
      -e HF_HOME=/models/huggingface \
      -e SENTENCE_TRANSFORMERS_HOME=/models/sentence-transformers \
      -e HF_HUB_DISABLE_XET=1 \
      -e HF_HUB_DISABLE_TELEMETRY=1 \
      "$IMAGE" >/dev/null
  fi
fi

for ((i = 0; i < READY_TIMEOUT; i++)); do
  if curl -fsS --connect-timeout 2 --max-time 5 \
    "$BASE_URL/health" >/dev/null 2>&1; then
    break
  fi

  container_status="$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null || true)"
  if [[ "$container_status" == "exited" || "$container_status" == "dead" ]]; then
    docker logs --tail 100 "$CONTAINER" >&2
    exit 1
  fi

  sleep 1
done

if ! curl -fsS --connect-timeout 2 --max-time 5 \
  "$BASE_URL/health" >/dev/null 2>&1; then
  echo "embedding-model liveness timeout" >&2
  docker logs --tail 100 "$CONTAINER" >&2
  exit 1
fi

if ! service_compatible; then
  echo "embedding-model is running but incompatible with the requested model configuration" >&2
  echo "expected embedding=$EMBEDDING_MODEL_ID dimensions=$EMBEDDING_DIMENSIONS embeddingOnly=$EMBEDDING_ONLY" >&2
  if [[ "$EMBEDDING_ONLY" != "true" ]]; then
    echo "expected reranker=$RERANK_MODEL_ID" >&2
  fi
  exit 1
fi

if [[ "$EMBEDDING_ONLY" == "true" ]]; then
  if ! curl -fsS --connect-timeout 2 --max-time "$PRELOAD_TIMEOUT" \
    -X POST "$BASE_URL/v1/models/preload" \
    -H 'Content-Type: application/json' \
    -d '{"models":["embedding"]}' >/dev/null; then
    echo "embedding-model embedding-only preload failed" >&2
    docker logs --tail 100 "$CONTAINER" >&2
    exit 1
  fi

  if embedding_ready; then
    echo "embedding-model is ready for QueryWeaver embedding use (${EMBEDDING_DIMENSIONS} dims)"
    exit 0
  fi

  echo "embedding-model embedding probe failed after preload" >&2
  docker logs --tail 100 "$CONTAINER" >&2
  exit 1
fi

for ((i = 0; i < READY_TIMEOUT; i++)); do
  if full_ready; then
    echo "embedding-model is ready (embedding + reranker)"
    exit 0
  fi
  sleep 1
done

echo "embedding-model readiness timeout" >&2
docker logs --tail 100 "$CONTAINER" >&2
exit 1
