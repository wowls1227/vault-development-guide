# Vault AppRole 샘플 앱 (Java 17)

AppRole auth method로 인증한 뒤 **HTTP API 요청을 받아 KV v2 시크릿을 읽고 저장하는** 샘플 앱입니다.

- 앱은 기동 후 API 요청을 기다리며 대기합니다.
- **한 번 로그인해서 받은 periodic 토큰을 주기적으로 갱신(renew-self)하며 계속 사용합니다.** secret-id로 로그인하는 것은 앱 시작과 장애 복구(토큰 강제 폐기 등) 때뿐입니다.
- **secret-id는 앱이 만들지 않습니다.** 신뢰된 오케스트레이터(CI/CD, 배포 시스템 등)가 발급해 파일로 전달하고, 앱은 그 파일을 감시하다가 **secret-id 조회(lookup)로 유효한지 확인한 뒤** 보관합니다. 이때 로그인하지 않으므로 토큰은 그대로입니다. (Trusted Orchestrator 패턴)
- **secret-id가 유효할 때만 로그인합니다.** 조회로 알아 둔 만료 시각이 지났거나 Vault가 거부한 secret-id로는 로그인을 보내지 않습니다. 실패가 쌓여 role-id가 잠기는 것을 막기 위해서입니다.
- 여러 API 요청 스레드가 **토큰 하나를 공유**합니다.
- Vault 라이브러리 없이 JDK `HttpClient` / `HttpServer` + Jackson만 사용합니다.

| 문서 | 내용 |
| --- | --- |
| [docs/DEVELOPMENT_GUIDE.md](docs/DEVELOPMENT_GUIDE.md) | 개발 가이드: 설계 고려사항, 작업 이력, 동작 로직, 동시성, 예외/에러 처리, 운영 |
| [docs/VAULT_API_GUIDE.md](docs/VAULT_API_GUIDE.md) | Java 함수별 Vault HTTP API와 실제 request/response |

## 요구 사항

| 항목 | 버전 | 용도 | 확인 명령 |
| --- | --- | --- | --- |
| JDK | **17 이상** | 빌드·실행 (`record`, `java.net.http.HttpClient` 사용) | `java -version` |
| Maven | **3.9 이상** (검증: 3.9.11) | 빌드. 3.8 이하는 기본 컴파일러 플러그인이 오래되어 Java 17 설정을 무시하고 빌드에 실패합니다 | `mvn -v` |
| Docker + Docker Compose v2 | Docker 20.10 이상, `docker compose` 명령 | Vault OSS dev 컨테이너 실행 | `docker version`, `docker compose version` |
| bash, curl | - | 스크립트 실행, API 호출 | `bash --version`, `curl --version` |
| jq | - | (선택) 예시 명령의 JSON 출력 정리 | `jq --version` |

- **OS**: macOS, Linux. Windows에서는 스크립트가 bash라서 WSL2에서 실행하세요.
- **포트**: `8200`(Vault), `8080`(앱 API)이 비어 있어야 합니다. 사용 중이면 `VAULT_HOST_PORT`, `API_PORT`로 바꿉니다.
- **Vault CLI는 따로 설치하지 않아도 됩니다.** 스크립트가 컨테이너 안의 `vault` CLI를 씁니다(`docker exec`).
- **네트워크**: 처음 빌드할 때 Maven Central에서 의존성을, 처음 기동할 때 Docker Hub에서 `hashicorp/vault` 이미지를 받습니다. 사내망이라면 Maven 프록시나 미러(`~/.m2/settings.xml`)와 Docker 프록시나 사내 레지스트리를 먼저 설정하세요.

### 설치: macOS (Homebrew)

```bash
brew install openjdk@17 maven jq

# openjdk@17 은 시스템 경로에 자동으로 연결되지 않으므로 PATH / JAVA_HOME 을 직접 설정합니다.
# (Apple Silicon 기준 경로. Intel Mac 은 /opt/homebrew 대신 /usr/local)
echo 'export JAVA_HOME="/opt/homebrew/opt/openjdk@17"' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

Docker는 [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/)을 설치하고 실행합니다(`brew install --cask docker`도 가능). Docker Desktop에는 Compose v2가 포함되어 있습니다.

### 설치: Linux (Ubuntu / Debian)

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk jq curl
```

### 설치: Linux (RHEL / Rocky / Alma 8·9)

```bash
sudo dnf install -y java-17-openjdk-devel jq curl
```

### 설치: Linux 공통 — Maven, Docker

배포판 패키지의 Maven은 3.6.x인 경우가 많습니다(Ubuntu 22.04, RHEL 9). 그래서 Apache 배포본으로 설치합니다.

```bash
MVN=3.9.11
curl -fsSLO https://archive.apache.org/dist/maven/maven-3/$MVN/binaries/apache-maven-$MVN-bin.tar.gz
sudo tar -xzf apache-maven-$MVN-bin.tar.gz -C /opt
echo "export PATH=/opt/apache-maven-$MVN/bin:\$PATH" >> ~/.bashrc
source ~/.bashrc
```

Docker Engine과 Compose 플러그인은 공식 문서대로 설치합니다([Ubuntu](https://docs.docker.com/engine/install/ubuntu/) · [Debian](https://docs.docker.com/engine/install/debian/) · [RHEL](https://docs.docker.com/engine/install/rhel/)). 설치한 뒤 `sudo` 없이 쓰려면 사용자를 `docker` 그룹에 추가하고 다시 로그인합니다.

```bash
sudo usermod -aG docker $USER
```

> RHEL 계열의 기본 컨테이너 도구는 Podman입니다. 이 프로젝트의 스크립트는 `docker` 명령을 호출하므로 Docker Engine 설치를 권장합니다.

### 설치 확인

```bash
java -version            # openjdk version "17..." 이상
mvn -v                   # Apache Maven 3.9.x 이상, 그리고 "Java version: 17..." 이상인지 확인
docker version           # Server 항목이 보여야 함 (Docker 데몬 실행 중)
docker compose version   # Docker Compose version v2.x
```

`mvn -v`에 나오는 Java 버전이 17보다 낮으면, Maven이 다른 JDK를 쓰고 있는 것입니다. `JAVA_HOME`을 JDK 17 경로로 설정하세요.

## 빠른 시작

```bash
# 1) Vault OSS dev 컨테이너 기동
docker compose up -d
#    8200 포트가 이미 사용 중이면: VAULT_HOST_PORT=18200 docker compose up -d

# 2) AppRole / 정책 / KV / 오케스트레이터 토큰 구성 + 최초 secret-id 전달
./scripts/setup-vault.sh
#    response wrapping 으로 전달하려면: VAULT_SECRET_ID_WRAPPED=true ./scripts/setup-vault.sh
#    포트를 바꿨다면 앞에 VAULT_HOST_PORT=18200 을 붙입니다

# 3) 빌드 + 실행 (API 대기 상태, Ctrl+C로 종료)
./scripts/run-app.sh

# 4) (다른 터미널) 오케스트레이터 역할: 새 secret-id 전달 쉘
./scripts/deliver-secret-id.sh            # 1회
./scripts/deliver-secret-id.sh --loop 60  # 60초마다
```



## 역할과 권한


| 주체                                  | Vault 정책                                                                                             | 할 수 있는 것                                                            | 할 수 없는 것         |
| ----------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | ---------------- |
| 앱 (`sample-app`)                    | `secret/data/sample-app/*` create/read/update `auth/approle/role/sample-app/secret-id/lookup` update | KV 읽기/저장, 전달받은 secret-id 조회, 자기 토큰 renew/lookup/revoke (default 정책) | **secret-id 발급** |
| 오케스트레이터 (`sample-app-orchestrator`) | `auth/approle/role/sample-app/secret-id` update                                                      | sample-app의 secret-id 발급                                            | KV 시크릿 읽기        |
| 관리자 (root)                          | -                                                                                                    | setup 스크립트로 초기 구성                                                   | -                |


앱 토큰이 유출되어도 그 토큰으로 secret-id를 새로 만들 수 없습니다. 오케스트레이터 토큰이 유출되어도 시크릿은 읽을 수 없습니다. 이 둘은 테스트에서 403으로 확인했습니다.

## secret-id 전달 방식


| 모드                                       | 파일 내용           | 앱 동작                                                                                                                    |
| ---------------------------------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 원문 (`VAULT_SECRET_ID_WRAPPED=false`, 기본) | secret-id       | 그대로 사용                                                                                                                  |
| Response wrapping (`=true`)              | 1회용 wrapping 토큰 | ① `sys/wrapping/lookup`으로 생성 경로가 `auth/approle/role/sample-app/secret-id`인지 확인 → ② `sys/wrapping/unwrap`으로 secret-id 획득 |


wrapping 모드의 장점은 다음과 같습니다.

- 오케스트레이터와 파일 경로가 secret-id 원문을 보지 않습니다.
- wrapping 토큰은 한 번만 풀 수 있습니다. 전달 도중 누가 먼저 열어 봤다면 앱의 unwrap이 실패하므로 바로 드러납니다.
- 다른 API로 만든 wrapping 토큰을 넣어도 생성 경로 검사에서 거부됩니다.

> wrapping 모드에서 앱을 **재시작**하려면, 먼저 `deliver-secret-id.sh`로 새 wrapping 토큰을 전달해야 합니다. 파일에 남아 있는 토큰은 이미 사용되었기 때문입니다.



## API


| 메서드            | 경로              | 설명                                                                 |
| -------------- | --------------- | ------------------------------------------------------------------ |
| `GET`          | `/v1/kv/{path}` | KV 시크릿 조회 → `{"path","version","data":{...}}`                      |
| `PUT` / `POST` | `/v1/kv/{path}` | KV 시크릿 저장. 바디는 key/value JSON 객체 → `{"path","version"}`            |
| `GET`          | `/health`       | 현재 토큰 만료 시각과 남은 TTL, secret-id 확인 여부와 만료 시각, 재로그인할 수 없는 사유 (원문 제외) |


`{path}`는 KV 마운트(`secret/`) 아래 경로입니다. 읽고 쓸 수 있는 범위는 Vault 정책이 결정하며, 기본 정책은 `sample-app/*`만 허용합니다.

```bash
curl -s localhost:8080/health | jq -r
curl -s localhost:8080/v1/kv/sample-app/config | jq -r
curl -s -X PUT localhost:8080/v1/kv/sample-app/api-test -d '{"api_key":"abc123","retry":3}' | jq -r
curl -s localhost:8080/v1/kv/sample-app/api-test | jq -r
```


| 응답 코드 | 의미                                                                                  |
| ----- | ----------------------------------------------------------------------------------- |
| 200   | 성공                                                                                  |
| 400   | 잘못된 경로(`..` 등) 또는 JSON 객체가 아닌 바디                                                    |
| 403   | Vault 정책에서 허용하지 않은 경로                                                               |
| 404   | 존재하지 않는 시크릿                                                                         |
| 405   | 지원하지 않는 메서드                                                                         |
| 502   | Vault에 연결할 수 없거나 Vault 내부 오류                                                        |
| 503   | 토큰이 죽었는데 재로그인할 수 없음 (secret-id 만료/거부, 로그인 잠김 대기). `/health`의 `loginBlocked`에서 사유 확인 |




## 구조

```
src/main/java/com/example/vault
├── App.java                       진입점. 컴포넌트 조립, 스레드 기동, API 대기, 종료 처리
├── config/VaultConfig.java        환경변수 → 설정 값
├── api/KvApiServer.java           KV 조회/저장 HTTP API (JDK HttpServer + 스레드 풀)
├── client/
│   ├── VaultHttpClient.java       Vault HTTP API 호출 (헤더, JSON, 오류 변환). 스레드 세이프
│   ├── VaultException.java        HTTP 상태 코드를 담은 예외
│   └── LoginUnavailableException.java  지금은 로그인할 수 없음 (503)
├── auth/
│   ├── AppRoleAuthenticator.java  role-id + secret-id 로그인 → 토큰
│   ├── TokenManager.java          공유 토큰/secret-id 관리, 토큰 무효 시 재로그인 (핵심)
│   ├── TokenRenewer.java          토큰 주기 갱신 (renew-self). 시작 시 periodic 토큰인지 확인
│   ├── SecretIdFileWatcher.java   오케스트레이터가 전달한 secret-id 파일 감시 (unwrap → 교체 요청)
│   ├── SecretIdLookup.java        secret-id 조회: 유효한지 / 언제 만료되는지 (로그인 없이)
│   ├── VaultToken.java            토큰 (불변)
│   └── SecretIdCredential.java    secret-id (불변)
├── kv/KvSecretClient.java         KV v2 조회/저장 ({mount}/data/{path})
├── worker/SecretWorker.java       (선택) 백그라운드 KV 조회 워커. 부하 테스트용
└── util/NamedThreadFactory.java   로그 확인용 스레드 이름 지정

scripts/
├── setup-vault.sh                 관리자: AppRole/정책/KV/오케스트레이터 토큰 구성 + 최초 전달
├── deliver-secret-id.sh           오케스트레이터: 새 secret-id 발급 → 파일에 원자적 교체
└── run-app.sh                     앱 빌드 + 실행
```



## 동작 흐름

```
오케스트레이터 ── secret-id 발급(전용 토큰) ──▶ Vault
      │
      └─ .vault/secret-id 에 원자적 교체 (tmp → mv)
                │  (앱은 읽기만 함)
                ▼
[vault-scheduler] SecretIdFileWatcher ─ 변경 감지 → (unwrap) → lookup 검증 → 보관 ─┐
[vault-scheduler] TokenRenewer ─ renew-self (periodic 토큰, 재로그인 없음) ────────┤
                                                                                  ▼
HTTP 요청 ─▶ [api-N] KvApiServer ─▶ KvSecretClient ─────────────────────────▶ TokenManager ─▶ Vault
```


| 대상           | 담당                                     | 주기                               | 동작                                                                                                                                     |
| ------------ | -------------------------------------- | -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 토큰           | `TokenRenewer`                         | `TOKEN_RENEW_INTERVAL_SEC` (20s) | `renew-self`로 TTL을 연장합니다. periodic 토큰이라 매번 period만큼 다시 채워지고 max_ttl 제한이 없으므로, **재로그인하지 않습니다.**                                         |
| secret-id 발급 | 오케스트레이터                                | 운영 환경에 맞게 (cron, 배포 시)           | 새 secret-id를 발급해 파일을 교체합니다.                                                                                                            |
| secret-id 적용 | `SecretIdFileWatcher` → `TokenManager` | `SECRET_ID_FILE_POLL_SEC` (5s)   | 파일 내용이 바뀌면 **secret-id 조회(lookup)로 검증**합니다. Vault에 있고 만료 전이면 보관하고(토큰 유지), 아니면 기존 것을 그대로 유지합니다. 토큰이 죽어 있어 조회할 수 없을 때만 로그인으로 검증하고 복구합니다. |
| 로그인          | `TokenManager`                         | 앱 시작, 장애 복구 때만                   | 보관한 secret-id가 **만료 전이고 Vault에서 거부된 적이 없을 때만** 로그인합니다.                                                                                 |


- **API 요청**: 매 요청마다 `TokenManager`에서 현재 토큰을 락 없이 가져옵니다. 동시 요청끼리 서로 막지 않습니다.
- **403 처리**: Vault는 토큰이 무효인 경우와 정책상 권한이 없는 경우 모두 403을 줍니다. 그래서 403을 받으면 `lookup-self`로 토큰이 유효한지 먼저 확인합니다.
  - 토큰이 무효이면 재로그인 후 한 번 재시도합니다. 여러 스레드가 동시에 403을 받아도 재로그인은 한 번만 합니다.
  - 토큰이 유효하면 정책 문제이므로 재로그인 없이 403을 그대로 돌려줍니다.
- **이전 토큰**: 토큰을 바꾸면, 이전 토큰은 `OLD_TOKEN_REVOKE_GRACE_SEC` 뒤에 `revoke-self`로 폐기합니다. 그 토큰으로 처리 중이던 요청이 실패하지 않게 하기 위해서입니다.
- **종료할 때**: API 서버를 내리고 워커와 스케줄러를 멈춘 뒤, 현재 토큰을 revoke합니다.



## 테스트 시나리오


| 시나리오              | 방법                                                                                                                                        | 기대 결과                                                                                                       |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| 토큰 유지             | 실행 후 period(60s)보다 오래 대기                                                                                                                  | `/health`의 `token.accessor`가 처음과 같음. `renew-self` 완료만 반복되고 `재로그인` 로그 없음                                     |
| secret-id 전달      | 앱 실행 중 `./scripts/deliver-secret-id.sh`                                                                                                   | 5초 안에 `새 secret-id 확인(lookup) 및 교체 완료 — 토큰은 유지`                                                             |
| 잘못된/폐기된 secret-id | `printf garbage > .vault/secret-id` 또는 destroy한 secret-id 기록                                                                              | `전달된 secret-id 가 Vault 에 없습니다`, 기존 secret-id 유지, API는 계속 200                                                |
| 앱 권한 분리           | 앱 토큰으로 `POST /v1/auth/approle/role/sample-app/secret-id`                                                                                  | 403                                                                                                         |
| 오케스트레이터 권한 분리     | 오케스트레이터 토큰으로 `GET /v1/secret/data/sample-app/config`                                                                                      | 403                                                                                                         |
| wrapping 가로채기     | wrapped 모드에서, 이미 unwrap된 토큰을 파일에 기록                                                                                                       | `wrapping 토큰이 유효하지 않습니다(... 이미 사용됨 ...)`, 기존 유지                                                             |
| wrapping 위조       | `sys/wrapping/wrap`으로 만든 토큰을 파일에 기록                                                                                                       | `wrapping 토큰 생성 경로가 다릅니다`, 기존 유지                                                                            |
| KV 조회/저장          | 위 curl 예시                                                                                                                                 | 저장할 때마다 `version`이 1씩 증가                                                                                    |
| 정책 밖 경로           | `curl -X PUT localhost:8080/v1/kv/other-app/x -d '{"a":"b"}'`                                                                             | 403. 로그에 `재로그인`이 찍히지 않음                                                                                     |
| periodic 확인       | 앱 시작 로그                                                                                                                                   | `periodic 토큰 확인: period=60s`. role에 period가 없으면 WARN                                                        |
| 토큰 강제 폐기          | 실행 중 `docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=root vault-dev vault token revoke -mode=path auth/approle/` 후 API 호출 | `토큰 무효(403)` 경고가 여러 스레드에서 찍히지만 `재로그인 완료`는 1회만 찍히고, 응답은 모두 200                                               |
| secret-id 만료 후 장애 | 보관 중인 secret-id의 만료 시각이 지난 뒤 토큰 강제 폐기                                                                                                     | `/health`에 `loginBlocked=SECRET_ID_EXPIRED`. **Vault에 로그인을 보내지 않고** API 503 → 새 secret-id 전달 시 그것으로 로그인해 복구 |
| 로그인 잠김            | 틀린 secret-id로 로그인 5회 → 새 secret-id 전달 → `vault write -f sys/locked-users/<mount_accessor>/unlock/<role_id>`                               | 새 secret-id를 검증 없이 보관하고, 60초 동안 로그인을 멈춘 뒤 보관한 secret-id로 회복                                                 |
| 동시 요청             | `seq 1 100                                                                                                                                | xargs -P 30 -I{} curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/v1/kv/sample-app/config            |
| 백그라운드 부하          | `WORKER_THREADS=4 ./scripts/run-app.sh`                                                                                                   | API와 별개로 워커가 계속 조회하며, 갱신 중에도 실패 없음                                                                          |




## 설정 (환경변수)


| 변수                                          | 기본값                          | 설명                                               |
| ------------------------------------------- | ---------------------------- | ------------------------------------------------ |
| `VAULT_ADDR`                                | `http://127.0.0.1:8200`      | Vault 주소                                         |
| `VAULT_NAMESPACE`                           | (없음)                         | Enterprise 네임스페이스                                |
| `VAULT_APPROLE_MOUNT`                       | `approle`                    | AppRole 마운트 경로                                   |
| `VAULT_ROLE_NAME`                           | `sample-app`                 | 역할 이름 (wrapping 생성 경로 검증에 사용)                    |
| `VAULT_ROLE_ID`                             | (필수)                         | role-id                                          |
| `VAULT_SECRET_ID_FILE`                      | (권장)                         | 오케스트레이터가 secret-id를 넣어 주는 파일. 앱은 읽기만 함           |
| `VAULT_SECRET_ID`                           | (없음)                         | 파일 대신 환경변수로 받는 secret-id. 재전달이 불가능하므로 테스트용       |
| `VAULT_SECRET_ID_WRAPPED`                   | `false`                      | 파일 내용을 wrapping 토큰으로 보고 unwrap                   |
| `SECRET_ID_FILE_POLL_SEC`                   | `5`                          | secret-id 파일 변경 확인 주기                            |
| `VAULT_KV_MOUNT`                            | `secret`                     | KV v2 마운트                                        |
| `TOKEN_RENEW_INTERVAL_SEC`                  | `20`                         | 토큰 갱신 주기 (token_period보다 충분히 짧게, 예: period의 1/3) |
| `OLD_TOKEN_REVOKE_GRACE_SEC`                | `5`                          | 이전 토큰을 폐기하기까지 대기 시간                              |
| `API_BIND_ADDR` / `API_PORT`                | `127.0.0.1` / `8080`         | API 서버 주소/포트                                     |
| `API_THREADS`                               | `8`                          | API 요청 처리 스레드 수                                  |
| `WORKER_THREADS`                            | `0`                          | 백그라운드 워커 수 (0 = 사용 안 함)                          |
| `VAULT_KV_PATH` / `WORKER_READ_INTERVAL_MS` | `sample-app/config` / `2000` | 워커가 읽을 경로와 주기                                    |
| `RUN_DURATION_SEC`                          | `0`                          | 실행 시간 (0 = 무한)                                   |


오케스트레이터 스크립트: `WRAP_TTL`(기본 `5m`)는 wrapping 토큰 유효시간입니다. 앱이 이 시간 안에 unwrap해야 합니다.

setup 스크립트의 AppRole 설정: `token_period=1m`(`TOKEN_PERIOD`로 변경, 운영은 1h 등), `token_max_ttl=0`, `token_explicit_max_ttl=0`, `secret_id_ttl=5m`, `secret_id_num_uses=0`.

## 부록

- **로그인 잠김(user lockout)**: Vault는 AppRole 로그인이 연속으로 실패하면(기본 5회) role-id를 15분 동안 잠급니다. 잠기면 올바른 secret-id로도 `403`이 납니다. 앱은 이미 거부된 secret-id로 재시도하지 않고, `403`을 받으면 60초 동안 로그인을 멈춥니다. 그래도 잠겼다면 관리자가 `vault read sys/locked-users`로 확인하고 `vault write -f sys/locked-users/<mount_accessor>/unlock/<role_id>`로 해제할 수 있습니다. 잠금 기준은 `vault auth tune -user-lockout-threshold / -user-lockout-duration`으로 조정합니다.
- **periodic 토큰과 secret-id**: 평소에는 secret-id를 쓰지 않습니다. 하지만 앱 재시작, 토큰 강제 폐기, period보다 긴 장애로 갱신을 놓친 경우에는 로그인해야 하고, 그때 유효한 secret-id가 필요합니다. 그래서 오케스트레이터는 secret-id TTL보다 짧은 주기로 계속 전달해야 합니다. 전달이 끊겨 만료되면 `/health`의 `loginBlocked=SECRET_ID_EXPIRED`로 미리 알 수 있습니다. 모니터링에 이 값을 걸어 두세요.
- `token_explicit_max_ttl`: 설정하면 periodic 토큰도 그 시간에 강제 만료됩니다. 보안 정책상 토큰을 주기적으로 새로 받아야 한다면 이 값을 설정하세요. 그 시점에 보관 중인 secret-id로 재로그인합니다(안전장치 경로).
- **만료된 secret-id**: Vault는 만료된 secret-id를 즉시 지우지 않습니다. 만료 직후 잠깐은 조회가 200으로 오고 로그인도 될 수 있습니다. 앱은 `expiration_time`을 직접 비교해서 더 엄격하게 판단합니다.
- **이전 secret-id**: 오케스트레이터가 따로 destroy하지 않고 TTL 만료에 맡깁니다. 더 빨리 무효화하려면, 오케스트레이터가 앱이 새 것을 적용한 뒤 이전 accessor를 destroy하도록 확장하세요. 정책에 `secret-id-accessor/destroy` 권한을 추가해야 합니다.
- `secret_id_num_uses`: 현재 0(무제한)이라 TTL 안에서 재로그인에 재사용됩니다. 1로 설정하면 더 엄격해지지만, 재로그인할 때마다 새 secret-id 전달이 필요합니다.
- **오케스트레이터 토큰 보관**: 이 샘플은 `.vault/orchestrator.env` 파일에 보관합니다. 운영에서는 CI/CD 시크릿 저장소에 두거나, 오케스트레이터 자체를 다른 auth method(예: Kubernetes, JWT/OIDC)로 인증하세요.
- **CIDR 바인딩**: role에 `secret_id_bound_cidrs` / `token_bound_cidrs`를 설정해, 탈취된 secret-id를 다른 위치에서 쓸 수 없게 하세요.
- **API 인증**: 이 샘플의 API에는 자체 인증이 없습니다. 기본으로 127.0.0.1에만 바인딩합니다. 외부에 열 때는 mTLS, API 키, 사내 인증 게이트웨이 등을 앞단에 두세요. 그리고 API로 노출할 경로를 Vault 정책에서 최소한으로 좁히세요.
- **응답에 시크릿 원문 포함**: GET 응답은 시크릿 값을 그대로 돌려줍니다. 호출하는 쪽의 로그나 프록시에 남지 않도록 주의하세요. 앱 로그에는 요청 메서드, 경로, 응답 코드만 남깁니다.
- **dev 모드**: 메모리 저장소이므로 컨테이너를 내리면 모든 설정이 사라집니다. 그 경우 `setup-vault.sh`를 다시 실행하세요.

