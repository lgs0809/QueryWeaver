#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODEL_ENV_FILE="${MODEL_ENV_FILE:-$PROJECT_ROOT/.env.local}"
BACKEND_BASE_URL="${QUERYWEAVER_BACKEND_BASE_URL:-http://127.0.0.1:${QW_BACKEND_PORT:-28065}}"

if [[ ! -f "$MODEL_ENV_FILE" ]]; then
  echo "Model environment file is missing: $MODEL_ENV_FILE" >&2
  echo "Copy config/model-env.example to .env.local and fill the model credential." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$MODEL_ENV_FILE"
set +a

required_variables=(
  OPENAI_BASE_URL
  OPENAI_API_KEY
  OPENAI_CHAT_MODEL
  OPENAI_EMBEDDING_API_KEY
  OPENAI_EMBEDDING_MODEL
)
for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "Required model variable is empty: $variable_name" >&2
    exit 1
  fi
done

embedding_base_url="${QUERYWEAVER_SEED_EMBEDDING_BASE_URL:-${QUERYWEAVER_EMBEDDING_BASE_URL:-${OPENAI_EMBEDDING_BASE_URL:-}}}"
if [[ -z "$embedding_base_url" ]]; then
  echo "QUERYWEAVER_EMBEDDING_BASE_URL or OPENAI_EMBEDDING_BASE_URL is required" >&2
  exit 1
fi

chat_completions_path="${OPENAI_CHAT_COMPLETIONS_PATH:-}"
if [[ -z "$chat_completions_path" ]]; then
  if [[ "${OPENAI_BASE_URL%/}" == */v1 ]]; then
    chat_completions_path="/chat/completions"
  else
    chat_completions_path="/v1/chat/completions"
  fi
fi

embedding_path="${OPENAI_EMBEDDINGS_PATH:-}"
if [[ -z "$embedding_path" ]]; then
  if [[ "${embedding_base_url%/}" == */v1 ]]; then
    embedding_path="/embeddings"
  else
    embedding_path="/v1/embeddings"
  fi
fi

require_success() {
  local operation="$1"
  node -e '
    let input = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", chunk => input += chunk);
    process.stdin.on("end", () => {
      let body;
      try { body = JSON.parse(input); }
      catch { console.error(`${process.argv[1]} returned invalid JSON`); process.exit(1); }
      if (body.success !== true) {
        console.error(`${process.argv[1]} failed: ${body.message || body.errorCode || "unknown error"}`);
        process.exit(1);
      }
    });
  ' "$operation"
}

list_configs() {
  curl -fsS --connect-timeout 2 --max-time 10 \
    "$BACKEND_BASE_URL/api/model-config/list"
}

find_config_id() {
  local provider="$1"
  local model_type="$2"
  SEED_PROVIDER="$provider" SEED_MODEL_TYPE="$model_type" node -e '
    let input = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", chunk => input += chunk);
    process.stdin.on("end", () => {
      const body = JSON.parse(input);
      const configs = Array.isArray(body.data) ? body.data : [];
      const match = configs.find(config =>
        config.provider === process.env.SEED_PROVIDER &&
        config.modelType === process.env.SEED_MODEL_TYPE);
      if (match?.id != null) process.stdout.write(String(match.id));
    });
  '
}

build_payload() {
  local model_type="$1"
  local config_id="${2:-}"
  SEED_MODEL_TYPE="$model_type" SEED_CONFIG_ID="$config_id" \
    SEED_CHAT_COMPLETIONS_PATH="$chat_completions_path" \
    SEED_EMBEDDING_BASE_URL="$embedding_base_url" \
    SEED_EMBEDDING_PATH="$embedding_path" \
    node -e '
      const isChat = process.env.SEED_MODEL_TYPE === "CHAT";
      const payload = {
        provider: isChat ? "queryweaver-openai-chat" : "queryweaver-local-embedding",
        baseUrl: (isChat ? process.env.OPENAI_BASE_URL : process.env.SEED_EMBEDDING_BASE_URL).replace(/\/$/, ""),
        apiKey: isChat ? process.env.OPENAI_API_KEY : process.env.OPENAI_EMBEDDING_API_KEY,
        modelName: isChat ? process.env.OPENAI_CHAT_MODEL : process.env.OPENAI_EMBEDDING_MODEL,
        modelType: process.env.SEED_MODEL_TYPE,
        temperature: isChat ? 0.2 : 0,
        maxTokens: isChat ? 8192 : null,
        completionsPath: isChat ? process.env.SEED_CHAT_COMPLETIONS_PATH : null,
        embeddingsPath: isChat ? null : process.env.SEED_EMBEDDING_PATH,
        isActive: false,
        proxyEnabled: false
      };
      if (process.env.SEED_CONFIG_ID) payload.id = Number(process.env.SEED_CONFIG_ID);
      process.stdout.write(JSON.stringify(payload));
    '
}

seed_and_activate() {
  local provider="$1"
  local model_type="$2"
  local config_id
  local payload

  config_id="$(list_configs | find_config_id "$provider" "$model_type")"
  payload="$(build_payload "$model_type" "$config_id")"

  if [[ -z "$config_id" ]]; then
    printf '%s' "$payload" \
      | curl -sS --connect-timeout 2 --max-time 15 \
        -H 'Content-Type: application/json' --data-binary @- \
        "$BACKEND_BASE_URL/api/model-config/add" \
      | require_success "add $model_type model configuration"
    config_id="$(list_configs | find_config_id "$provider" "$model_type")"
    if [[ -z "$config_id" ]]; then
      echo "Seeded $model_type model configuration cannot be found" >&2
      exit 1
    fi
    payload="$(build_payload "$model_type" "$config_id")"
  else
    printf '%s' "$payload" \
      | curl -sS --connect-timeout 2 --max-time 15 -X PUT \
        -H 'Content-Type: application/json' --data-binary @- \
        "$BACKEND_BASE_URL/api/model-config/update" \
      | require_success "update $model_type model configuration"
  fi

  # Exercise the product validation gate. The backend decrypts the stored
  # credential, performs a real call, and records PASSED/FAILED before activation.
  printf '%s' "$payload" \
    | curl -sS --connect-timeout 2 --max-time 90 \
      -H 'Content-Type: application/json' --data-binary @- \
      "$BACKEND_BASE_URL/api/model-config/test" \
    | require_success "validate $model_type model configuration"

  curl -sS --connect-timeout 2 --max-time 15 -X POST \
    "$BACKEND_BASE_URL/api/model-config/activate/$config_id" \
    | require_success "activate $model_type model configuration"

  echo "$model_type model configuration is validated and active"
}

seed_and_activate "queryweaver-local-embedding" "EMBEDDING"
seed_and_activate "queryweaver-openai-chat" "CHAT"
