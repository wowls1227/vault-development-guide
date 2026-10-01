#!/usr/bin/env bash
# ------------------------------------------------------------------------------
# 신뢰된 오케스트레이터 역할: 새 secret-id 를 발급해 앱의 secret-id 파일에 전달한다.
# (운영에서는 CI/CD 파이프라인, 배포 시스템, cron 잡 등이 이 역할을 한다)
#
#  - root 가 아닌 오케스트레이터 전용 토큰(.vault/orchestrator.env)을 사용한다.
#    이 토큰은 sample-app role 의 secret-id 발급만 할 수 있고 KV 시크릿에는 접근할 수 없다.
#  - 파일은 임시 파일에 쓴 뒤 mv 로 교체한다. 앱이 쓰다 만 파일을 읽는 일이 없도록 하기 위함.
#  - ORCH_WRAPPED=true 면 secret-id 원문 대신 response-wrapping 토큰을 전달한다.
#    (1회용이고 WRAP_TTL 안에 앱이 unwrap 해야 함. 앱도 VAULT_SECRET_ID_WRAPPED=true 로 실행)
#
# 사용법
#   ./scripts/deliver-secret-id.sh             # 1회 전달
#   ./scripts/deliver-secret-id.sh --loop 60   # 60초마다 전달 (cron/사이드카 흉내, Ctrl+C 로 종료)
# ------------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f .vault/orchestrator.env ]]; then
  echo ".vault/orchestrator.env 가 없습니다. 먼저 ./scripts/setup-vault.sh 를 실행하세요." >&2
  exit 1
fi
source .vault/orchestrator.env

CONTAINER=vault-dev
WRAP_TTL=${WRAP_TTL:-5m}

# 컨테이너 안의 vault CLI 를 오케스트레이터 토큰으로 실행하는 헬퍼
v() { docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN="$ORCH_VAULT_TOKEN" "$CONTAINER" vault "$@"; }

deliver() {
  local value
  if [[ "$ORCH_WRAPPED" == "true" ]]; then
    # 응답을 wrapping 토큰으로 감싸서 받는다. 오케스트레이터는 secret-id 원문을 보지 않는다.
    value=$(v write -wrap-ttl="$WRAP_TTL" -field=wrapping_token -f "auth/approle/role/$ORCH_ROLE_NAME/secret-id")
  else
    value=$(v write -f -field=secret_id "auth/approle/role/$ORCH_ROLE_NAME/secret-id")
  fi

  # 같은 디렉터리에 임시 파일 생성(권한 600) → 원자적 교체
  local dir tmp
  dir=$(dirname "$ORCH_TARGET_FILE")
  mkdir -p "$dir"
  tmp=$(mktemp "$dir/.secret-id.XXXXXX")
  chmod 600 "$tmp"
  printf '%s' "$value" > "$tmp"
  mv -f "$tmp" "$ORCH_TARGET_FILE"

  echo "[$(date +%T)] 새 secret-id 전달 완료 (wrapped=$ORCH_WRAPPED) → $ORCH_TARGET_FILE"
}

if [[ "${1:-}" == "--loop" ]]; then
  interval=${2:-60}
  echo "secret-id 주기 전달 시작: ${interval}초 간격"
  while true; do
    deliver
    sleep "$interval"
  done
else
  deliver
fi
