#!/usr/bin/env bash
# ------------------------------------------------------------------------------
# Vault dev 컨테이너에 샘플 앱용 설정을 구성한다. (root 토큰으로 실행 = Vault 관리자 역할)
#
#  1. AppRole auth method 활성화
#  2. KV v2 (secret/) 에 샘플 시크릿 저장
#  3. 앱 정책 작성 (KV 읽기/저장 + 자기 secret-id 조회. secret-id 발급 권한 없음)
#  4. 오케스트레이터 정책 작성 (sample-app role 의 secret-id 발급만)
#  5. AppRole 역할 생성 (periodic 토큰: 갱신만 계속하면 재로그인 없이 사용)
#  6. 오케스트레이터 토큰 발급
#  7. role-id 조회 + 오케스트레이터로 최초 secret-id 전달
#
# 옵션
#  VAULT_SECRET_ID_WRAPPED=true ./scripts/setup-vault.sh  → secret-id 를 response-wrapping 토큰으로 전달
#  TOKEN_PERIOD=1h ./scripts/setup-vault.sh               → 토큰 period (기본 1m: dev 에서 갱신 동작을 보기 위해 짧게)
#
# 산출물
#  - approle.env               : 앱 실행용 환경변수
#  - .vault/orchestrator.env   : 오케스트레이터(deliver-secret-id.sh) 설정 + 토큰
#  - .vault/secret-id          : 오케스트레이터가 전달한 secret-id (앱은 읽기만 함)
# ------------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

CONTAINER=vault-dev
ROLE=sample-app
# docker-compose 에서 호스트 포트를 바꿨다면 같은 값을 지정 (예: VAULT_HOST_PORT=18200 ./scripts/setup-vault.sh)
VAULT_HOST_PORT=${VAULT_HOST_PORT:-8200}
WRAPPED=${VAULT_SECRET_ID_WRAPPED:-false}
TOKEN_PERIOD=${TOKEN_PERIOD:-1m}

# 컨테이너 안의 vault CLI 를 root 토큰으로 실행하는 헬퍼
v() { docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=root "$CONTAINER" vault "$@"; }

echo "==> Vault 기동 대기"
for i in $(seq 1 30); do
  v status >/dev/null 2>&1 && break
  if [[ $i -eq 30 ]]; then
    echo "Vault 컨테이너($CONTAINER)가 응답하지 않습니다. docker compose up -d 가 성공했는지 확인하세요." >&2
    exit 1
  fi
  sleep 1
done

echo "==> 1. AppRole auth method 활성화"
if ! v auth list | grep -q '^approle/'; then
  v auth enable approle
fi

echo "==> 2. KV v2 샘플 시크릿 저장 (secret/sample-app/config)"
v kv put secret/sample-app/config \
  username=app_user \
  password='S3cr3t-P@ss' \
  db_url='jdbc:postgresql://db.example.local:5432/app' >/dev/null

echo "==> 3. 앱 정책 작성 ($ROLE)"
v policy write "$ROLE" - <<POLICY
# KV v2 시크릿 읽기/저장 (KV v2 는 실제 경로에 data/ 가 들어간다)
# 앱 API 로 접근할 수 있는 범위는 이 정책이 결정한다. sample-app/ 밖의 경로는 403.
path "secret/data/sample-app/*" {
  capabilities = ["create", "read", "update"]
}

# 전달받은 secret-id 가 유효한지 / 언제 만료되는지 조회 (로그인하지 않고 검증하기 위함)
# 이미 가지고 있는 secret-id 의 정보만 볼 수 있고, secret-id 를 만들 수는 없다.
path "auth/approle/role/$ROLE/secret-id/lookup" {
  capabilities = ["update"]
}

# secret-id 발급 권한은 일부러 주지 않는다. (토큰이 유출되어도 secret-id 를 만들 수 없음)
# 토큰 갱신(renew-self) / 조회(lookup-self) / 폐기(revoke-self)는 default 정책에 포함되어 있다.
POLICY

echo "==> 4. 오케스트레이터 정책 작성 ($ROLE-orchestrator)"
v policy write "$ROLE-orchestrator" - <<POLICY
# sample-app role 의 secret-id 발급만 허용 (KV 등 시크릿 자체에는 접근 불가)
path "auth/approle/role/$ROLE/secret-id" {
  capabilities = ["update"]
}
POLICY

echo "==> 5. AppRole 역할 생성"
# token_period=1m        : periodic 토큰. period 안에 renew 하면 TTL 이 다시 period 로 채워지고, max_ttl 제한이 없다.
#                          → 한 번 로그인한 토큰을 갱신하며 계속 사용 (앱은 20초마다 renew). 운영에서는 1h 등으로 늘린다.
# token_max_ttl=0        : (periodic 토큰에는 적용되지 않지만) 이전 설정이 남지 않도록 명시적으로 비움
# token_explicit_max_ttl=0 : 설정하면 periodic 토큰도 이 시간에 강제 만료되므로 비워 둔다
# secret_id_ttl=5m       : secret-id 수명. 오케스트레이터 전달 주기보다 충분히 길게
#                          (secret-id 는 앱 시작 / 장애 복구 로그인에만 쓰이지만, 그때 유효해야 하므로 계속 전달받는다)
# secret_id_num_uses=0   : secret-id 사용 횟수 무제한 (TTL 안에서는 재로그인에 재사용 가능)
v write "auth/approle/role/$ROLE" \
  token_policies="$ROLE" \
  token_period="$TOKEN_PERIOD" \
  token_max_ttl=0 \
  token_explicit_max_ttl=0 \
  secret_id_ttl=5m \
  secret_id_num_uses=0 >/dev/null

echo "==> 6. 오케스트레이터 토큰 발급"
# -period=24h : 24시간 안에 갱신하면 계속 쓸 수 있는 토큰
# -orphan     : 부모(root) 토큰이 폐기되어도 유지
ORCH_TOKEN=$(v token create -policy="$ROLE-orchestrator" -period=24h -orphan -field=token)

mkdir -p .vault
umask 077
cat > .vault/orchestrator.env <<ENV
# deliver-secret-id.sh 가 읽는 오케스트레이터 설정 (dev 테스트용. 운영에서는 CI/CD 의 시크릿 저장소에 보관)
ORCH_VAULT_TOKEN=$ORCH_TOKEN
ORCH_ROLE_NAME=$ROLE
ORCH_TARGET_FILE=$(pwd)/.vault/secret-id
ORCH_WRAPPED=$WRAPPED
ENV

echo "==> 7. role-id 조회 + 최초 secret-id 전달"
ROLE_ID=$(v read -field=role_id "auth/approle/role/$ROLE/role-id")
./scripts/deliver-secret-id.sh

cat > approle.env <<ENV
export VAULT_ADDR=http://127.0.0.1:$VAULT_HOST_PORT
export VAULT_ROLE_NAME=$ROLE
export VAULT_ROLE_ID=$ROLE_ID
export VAULT_SECRET_ID_FILE=$(pwd)/.vault/secret-id
export VAULT_KV_MOUNT=secret
export VAULT_KV_PATH=sample-app/config
# 아래 값은 실행 시 환경변수로 덮어쓸 수 있다. (예: RUN_DURATION_SEC=100 ./scripts/run-app.sh)
export VAULT_SECRET_ID_WRAPPED=\${VAULT_SECRET_ID_WRAPPED:-$WRAPPED}
export SECRET_ID_FILE_POLL_SEC=\${SECRET_ID_FILE_POLL_SEC:-5}
export TOKEN_RENEW_INTERVAL_SEC=\${TOKEN_RENEW_INTERVAL_SEC:-20}
export OLD_TOKEN_REVOKE_GRACE_SEC=\${OLD_TOKEN_REVOKE_GRACE_SEC:-5}
export API_BIND_ADDR=\${API_BIND_ADDR:-127.0.0.1}
export API_PORT=\${API_PORT:-8080}
export API_THREADS=\${API_THREADS:-8}
export WORKER_THREADS=\${WORKER_THREADS:-0}
export WORKER_READ_INTERVAL_MS=\${WORKER_READ_INTERVAL_MS:-2000}
export RUN_DURATION_SEC=\${RUN_DURATION_SEC:-0}
ENV

echo
echo "완료."
echo "  role-id          : $ROLE_ID"
echo "  secret-id 파일   : .vault/secret-id (wrapped=$WRAPPED)"
echo "  앱 환경변수      : approle.env"
echo "  오케스트레이터   : .vault/orchestrator.env"
echo
echo "앱 실행          : ./scripts/run-app.sh"
echo "secret-id 재전달 : ./scripts/deliver-secret-id.sh          (1회)"
echo "                   ./scripts/deliver-secret-id.sh --loop 60 (60초마다)"
