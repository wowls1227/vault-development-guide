#!/usr/bin/env bash
# ------------------------------------------------------------------------------
# 앱 빌드 + 실행
#  - approle.env (setup-vault.sh 가 생성) 를 읽어 환경변수를 설정한다.
#  - 실행 중 환경변수를 덮어쓰려면: RUN_DURATION_SEC=100 ./scripts/run-app.sh
# ------------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ ! -f approle.env ]]; then
  echo "approle.env 가 없습니다. 먼저 ./scripts/setup-vault.sh 를 실행하세요." >&2
  exit 1
fi

source approle.env

mvn -q -DskipTests package
exec java -jar target/vault-approle-sample.jar
