# Vault API 정의서

이 프로젝트(앱, 오케스트레이터 스크립트, 관리자 셋업 스크립트)가 사용하는 **모든 API의 명세**입니다. API마다 호출 주체, 인증, 필요 권한, 요청 파라미터, 응답 필드, 오류를 정리했습니다.

- 기준: Vault OSS **2.0.3** dev 서버(`-dev-tls`)에서 2026-10-23에 직접 호출해 확인한 값입니다. 토큰과 인증서 값은 일부만 남기고 줄였습니다.
- Java 함수 기준의 설명은 [VAULT_API_GUIDE.md](VAULT_API_GUIDE.md), KV 저장·조회·업데이트·변경·삭제와 메타데이터, AppRole/Certificate 인증은 [KV_API_GUIDE.md](KV_API_GUIDE.md)를 참고하세요.
- Vault HTTP API 원문: <https://developer.hashicorp.com/vault/api-docs>
- 요청 예시에는 네임스페이스 `team-a`를 쓰는 경우의 `X-Vault-Namespace` 헤더를 함께 적었습니다. root 네임스페이스나 OSS에서는 이 헤더를 빼면 됩니다.

---

## 1. 공통 규약

### 1.1 Vault API 요청

| 항목 | 내용 |
| --- | --- |
| Base URL | `{VAULT_ADDR}/v1/` (예: `http://127.0.0.1:8200/v1/`) |
| 인증 헤더 | `X-Vault-Token: <토큰>` — 로그인, `sys/wrapping/lookup`은 토큰 없이 호출 |
| 네임스페이스 헤더 | `X-Vault-Namespace: <ns>` — Enterprise 네임스페이스를 쓸 때 **모든 Vault 요청(로그인 포함)**에 붙임. 상세는 1.5절 |
| Wrapping 헤더 | `X-Vault-Wrap-TTL: 5m` — 응답을 1회용 wrapping 토큰으로 받을 때 (SID-02) |
| Content-Type | `application/json`. 단, `PATCH`는 `application/merge-patch+json` (KV_API_GUIDE 참고) |
| 클라이언트 인증서 | Certificate 인증(AUTH-02)과 그 토큰의 갱신(TOK-02)에만 필요. Vault 리스너가 TLS여야 함 |
| `LIST` 메서드 | `LIST` 대신 `GET ...?list=true`로 호출해도 됩니다. |

### 1.2 Vault 응답 공통 구조

성공 응답은 아래 형태이며, API에 따라 `data` 또는 `auth`에 결과가 들어갑니다. 바디가 없는 성공은 `204 No Content`입니다.

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `request_id` | string | 요청 ID (감사 로그와 대조할 때 사용) |
| `data` | object \| null | 일반 API 결과 (KV 조회, secret-id 발급 등) |
| `auth` | object \| null | 인증 API 결과 (로그인, 토큰 갱신) |
| `wrap_info` | object \| null | `X-Vault-Wrap-TTL`을 붙인 경우 wrapping 토큰 정보 |
| `warnings` | string[] \| null | 경고 (예: 무시된 파라미터, max_ttl에 막혀 줄어든 TTL) |
| `lease_id` / `lease_duration` / `renewable` | | 동적 시크릿 lease 정보. 이 프로젝트의 API에서는 비어 있음 |
| `mount_type` | string | 응답을 만든 엔진 종류 (`kv`, `approle` 등) |

### 1.3 Vault 오류 응답

형식: `{"errors": ["메시지", ...]}`

| 상태 | 의미 | 대표 메시지 |
| --- | --- | --- |
| 400 | 잘못된 요청 / 인증 실패 | `invalid role or secret ID`, `client certificate must be supplied`, `check-and-set parameter did not match the current version` |
| 403 | 토큰 무효(만료/폐기) 또는 정책상 권한 없음, AppRole 잠김 | `permission denied` (+ `invalid token`) |
| 404 | 대상 없음 | `{"errors":[]}` 또는 KV 삭제 버전 조회 시 `data: null` 바디 |
| 415 | `PATCH`인데 Content-Type이 `application/merge-patch+json`이 아님 | `PATCH requires Content-Type of application/merge-patch+json` |
| 5xx | Vault 내부 오류 / sealed | |

> 403은 "토큰 무효"와 "권한 없음"을 같은 코드로 줍니다. 앱은 TOK-01(`lookup-self`)로 토큰이 유효한지 확인해 둘을 구분합니다.

### 1.4 앱 API 공통

| 항목 | 내용 |
| --- | --- |
| Base URL | `http://{API_BIND_ADDR}:{API_PORT}` (기본 `http://127.0.0.1:8080`) |
| 인증 | 없음. 기본값으로 127.0.0.1에만 바인딩 |
| 응답 Content-Type | `application/json; charset=utf-8` |
| 오류 형식 | `{"error": "메시지"}` |
| 상태 코드 변환 | Vault의 400/403/404/503은 그대로, 그 외(네트워크 오류, Vault 5xx)는 502 |

### 1.5 네임스페이스 (`X-Vault-Namespace`)

Vault Enterprise(HCP Vault 포함)의 네임스페이스는 하나의 Vault 안을 조직·팀별로 나눈 독립 공간입니다. 인증 방식, 시크릿 엔진, 정책, 토큰이 네임스페이스마다 따로 있습니다.

| 항목 | 내용 |
| --- | --- |
| 지정 방법 | 요청 헤더 `X-Vault-Namespace: <ns>` (권장). Enterprise는 경로 앞에 붙이는 방식(`/v1/<ns>/secret/data/...`)도 지원 |
| 값 형식 | `team-a`, 중첩은 `parent/child`. 앞뒤 `/` 없이. root 네임스페이스는 헤더를 생략 |
| 적용 대상 | 2장의 **모든 Vault API** — 로그인(AUTH), 토큰(TOK), secret-id(SID), wrapping(WRP), KV, 관리(ADM). 앱 API(APP)는 해당 없음 (앱이 Vault를 부를 때 내부적으로 붙임) |
| 헤더와 경로 prefix를 함께 쓰면 | 둘이 이어 붙여져 하위 네임스페이스로 해석됨. 한 가지만 사용 |
| 토큰 범위 | 토큰은 **로그인한 네임스페이스와 그 하위**에서만 유효. 로그인 때와 같은 네임스페이스 헤더로 이후 요청(갱신, KV)을 보내야 함 |
| 마운트 / 정책 경로 | 네임스페이스 기준 상대 경로. `team-a`의 정책에 쓴 `secret/data/sample-app/*`는 `team-a/secret/data/sample-app/*`를 가리킴 |
| wrapping 토큰 | 만든 네임스페이스에서 lookup / unwrap |
| CLI | `export VAULT_NAMESPACE=team-a` 또는 `vault <명령> -namespace=team-a` (CLI가 헤더를 붙여 줌) |
| 앱 | `VAULT_NAMESPACE` 환경변수를 지정하면 `VaultHttpClient`가 **로그인을 포함한 모든 Vault 요청**에 헤더를 붙임 (토큰 없는 요청도 포함). 지정하지 않으면 헤더 없음 (root) |
| OSS | 네임스페이스 기능이 없습니다. **헤더를 보내도 무시하고 root에서 처리**하며 오류도 나지 않습니다(2.0.3에서 확인). 경로 prefix 방식은 `404`. OSS에서 테스트한 뒤 Enterprise로 옮길 때 네임스페이스 값이 맞는지 따로 확인해야 합니다 |

```http
GET /v1/secret/data/sample-app/config
X-Vault-Namespace: team-a
X-Vault-Token: (team-a 에서 로그인한 토큰)
```

---

## 2. API 목록

| ID | 구분 | Method | Path | 호출 주체 | 토큰 | 필요 권한 (capability) | 앱 구현 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| APP-01 | 앱 API | GET | `/v1/kv/{path}` | 외부 클라이언트 | 없음 | (내부적으로 KV-01) | O |
| APP-02 | 앱 API | PUT, POST | `/v1/kv/{path}` | 외부 클라이언트 | 없음 | (내부적으로 KV-02) | O |
| APP-03 | 앱 API | GET | `/health` | 모니터링 | 없음 | 없음 | O |
| AUTH-01 | 인증 | POST | `/v1/auth/approle/login` | 앱 | 없음 | 없음 (role-id + secret-id) | O |
| AUTH-02 | 인증 | POST | `/v1/auth/cert/login` | 앱 | 없음 | 없음 (클라이언트 인증서) | X |
| TOK-01 | 토큰 | GET | `/v1/auth/token/lookup-self` | 앱 | 앱 토큰 | `default` 정책 | O |
| TOK-02 | 토큰 | POST | `/v1/auth/token/renew-self` | 앱, 오케스트레이터 | 자기 토큰 | `default` 정책 | O |
| TOK-03 | 토큰 | POST | `/v1/auth/token/revoke-self` | 앱 | 폐기할 토큰 | `default` 정책 | O |
| SID-01 | secret-id | POST | `/v1/auth/approle/role/{role}/secret-id/lookup` | 앱 | 앱 토큰 | `update` | O |
| SID-02 | secret-id | POST | `/v1/auth/approle/role/{role}/secret-id` | 오케스트레이터 | 오케스트레이터 토큰 | `update` | 스크립트 |
| WRP-01 | wrapping | POST | `/v1/sys/wrapping/lookup` | 앱 | 없음 | 없음 | O |
| WRP-02 | wrapping | POST | `/v1/sys/wrapping/unwrap` | 앱 | wrapping 토큰 | 없음 | O |
| KV-01 | KV v2 | GET | `/v1/{mount}/data/{path}` | 앱 | 앱 토큰 | `read` | O |
| KV-02 | KV v2 | POST | `/v1/{mount}/data/{path}` | 앱, 관리자 | 앱/관리자 토큰 | `create`(신규), `update`(기존) | O |
| KV-03 ~ KV-12 | KV v2 | PATCH, DELETE, LIST 등 | `/v1/{mount}/{data,metadata,delete,undelete,destroy,subkeys}/{path}` | 앱 | 앱 토큰 | 작업별 | X → [KV_API_GUIDE.md](KV_API_GUIDE.md) |
| ADM-01 | 관리 | POST | `/v1/sys/auth/{mount}` | 관리자 | root | `sudo` | 스크립트 |
| ADM-02 | 관리 | PUT | `/v1/sys/policies/acl/{name}` | 관리자 | root | `create`, `update` | 스크립트 |
| ADM-03 | 관리 | POST | `/v1/auth/approle/role/{role}` | 관리자 | root | `create`, `update` | 스크립트 |
| ADM-04 | 관리 | GET | `/v1/auth/approle/role/{role}/role-id` | 관리자 | root | `read` | 스크립트 |
| ADM-05 | 관리 | POST | `/v1/auth/token/create-orphan` | 관리자 | root | `sudo` | 스크립트 |
| ADM-06 | 관리 | POST | `/v1/auth/cert/certs/{name}` | 관리자 | root | `create`, `update` | (Cert 인증 구성 시) |

- `{mount}`: KV v2 마운트 경로. 이 프로젝트는 `secret` (`VAULT_KV_MOUNT`)
- `{role}`: AppRole 역할 이름. 이 프로젝트는 `sample-app` (`VAULT_ROLE_NAME`)
- AppRole 마운트 경로는 `approle` (`VAULT_APPROLE_MOUNT`), Certificate 마운트 경로는 `cert`
- Vault API(앱 API 제외)는 네임스페이스를 쓸 때 모두 `X-Vault-Namespace` 헤더가 필요합니다 (1.5절).
- "스크립트"는 `scripts/setup-vault.sh`(관리자), `scripts/deliver-secret-id.sh`(오케스트레이터)가 `vault` CLI로 호출하는 API입니다.

---

## 3. 앱 API

### APP-01. KV 시크릿 조회

| 항목 | 내용 |
| --- | --- |
| Method / Path | `GET /v1/kv/{path}` |
| 처리 | `KvSecretClient.read(path)` → KV-01 |
| 경로 제한 | `[A-Za-z0-9_.-]` 세그먼트만 허용, `.` / `..` 세그먼트 거부 |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `path` | path | string | O | KV 마운트 아래 경로. 예: `sample-app/config` → Vault `secret/data/sample-app/config` |

**응답** `200`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `path` | string | 요청한 경로 |
| `version` | int | 조회한 버전 (최신) |
| `data` | object | 시크릿 key/value |

```json
{"path":"sample-app/config","version":3,"data":{"db_url":"jdbc:postgresql://db.example.local:5432/app","password":"S3cr3t-P@ss","username":"app_user"}}
```

**오류**: `400` 잘못된 경로 · `403` Vault 정책 거부 · `404` 시크릿 없음 · `503` Vault 로그인 불가 상태 · `502` Vault 연결 실패

### APP-02. KV 시크릿 저장

| 항목 | 내용 |
| --- | --- |
| Method / Path | `PUT /v1/kv/{path}` (`POST` 동일) |
| 처리 | `KvSecretClient.write(path, body)` → KV-02. 바디를 `{"data": ...}`로 감싸는 것은 앱이 처리 |
| 바디 제한 | 비어 있지 않은 JSON 객체, 최대 1MB |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `path` | path | string | O | APP-01과 동일 |
| (바디) | body | object | O | 저장할 key/value. 값은 문자열, 숫자, 객체 등 JSON 값 |

**응답** `200`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `path` | string | 저장한 경로 |
| `version` | int | 새로 만들어진 버전 |

```http
PUT /v1/kv/sample-app/api-test HTTP/1.1
Content-Type: application/json

{"api_key":"abc123","retry":3,"nested":{"a":1}}
```
```json
{"version":1,"path":"sample-app/api-test"}
```

**오류**: `400` 잘못된 경로 / JSON 객체가 아닌 바디 / 1MB 초과 · `403` · `405` 지원하지 않는 메서드(`DELETE`, `PATCH` 등) · `502` · `503`

> 현재 앱 API는 조회(GET)와 저장(PUT/POST)만 제공합니다. 변경(PATCH), 삭제, 메타데이터 API는 [KV_API_GUIDE.md](KV_API_GUIDE.md) 10장의 확장안을 참고하세요.

### APP-03. 상태 확인

| 항목 | 내용 |
| --- | --- |
| Method / Path | `GET /health` |
| 처리 | `TokenManager` 상태만 읽음. Vault 호출 없음 |

**응답** `200`(토큰 유효) / `503`(토큰 없음 또는 만료)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `status` | string | `UP` / `DOWN` |
| `loginBlocked` | string | (있을 때만) 재로그인할 수 없는 사유: `SECRET_ID_EXPIRED`, `SECRET_ID_REJECTED`, `LOGIN_LOCKED_OUT (until …)` |
| `token.accessor` | string | 현재 토큰 accessor |
| `token.expiresAt` | string | 앱이 계산한 토큰 만료 시각 |
| `token.remainingSec` | long | 남은 초 |
| `secretId.accessor` | string | secret-id accessor |
| `secretId.verified` | boolean | SID-01로 확인했는지 |
| `secretId.expiresAt` | string \| null | secret-id 만료 시각 |
| `secretId.remainingSec` | long | 남은 초 (`-1` = 모름 또는 만료 없음) |
| `secretId.loadedAt` | string | 파일에서 읽은 시각 |

```json
{"status":"UP","token":{"expiresAt":"2026-10-01T01:29:24.879650Z","remainingSec":59,"accessor":"VTLi7XbWMyHJpPSoFaK0WVx0"},"secretId":{"accessor":"aee423a8-b631-afa0-1fab-9de365c51c41","verified":true,"expiresAt":"2026-10-01T01:33:21.936776635Z","remainingSec":296,"loadedAt":"2026-10-01T01:28:24.822919Z"}}
```

---

## 4. 인증 API

### AUTH-01. AppRole 로그인

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/{approle_mount}/login` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `AppRoleAuthenticator.login` (앱 시작, 장애 복구 때만) |
| 토큰 / 권한 | 없음 |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `role_id` | body | string | O | 역할 ID (고정 값, 배포 시 주입) |
| `secret_id` | body | string | O | secret-id (오케스트레이터가 전달) |

**응답** `200` — `auth`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `auth.client_token` | string | 이후 요청의 `X-Vault-Token` |
| `auth.accessor` | string | 토큰 식별자 (로그용) |
| `auth.lease_duration` | int | 토큰 TTL(초). periodic 토큰이면 period |
| `auth.renewable` | boolean | TOK-02로 갱신 가능 여부 |
| `auth.policies` / `token_policies` | string[] | 토큰에 붙은 정책 |
| `auth.metadata.role_name` | string | 로그인한 역할 |
| `auth.entity_id` | string | Identity entity ID (KV 메타데이터의 `created_by.entity_id`와 같은 값) |
| `auth.token_type` / `orphan` / `num_uses` | | `service` / `true` / `0` |

```http
POST /v1/auth/approle/login
X-Vault-Namespace: team-a
Content-Type: application/json

{"role_id":"3f731e94-7a80-b363-835d-39e9f335713c","secret_id":"2b4c4263-cf29-331f-89a5-f8b79d251dbe"}
```
```json
{
  "auth": {
    "client_token": "hvs.CAESIEbrQn1iUBpY...(생략)",
    "accessor": "0c3IJOF5BVH8wUocmNE43OGt",
    "entity_id": "577dfcb5-1f82-1b78-2970-8c1d1cf9f91b",
    "lease_duration": 60,
    "metadata": { "role_name": "sample-app" },
    "policies": ["default", "sample-app", "sample-app-kv"],
    "renewable": true,
    "token_type": "service",
    "orphan": true,
    "num_uses": 0
  }
}
```

**오류**

| 상황 | 응답 |
| --- | --- |
| secret-id가 틀렸거나 만료/폐기됨 | `400 {"errors":["invalid role or secret ID"]}` |
| 연속 실패로 role-id 잠김 (기본 5회 → 15분) | `403 {"errors":["permission denied"]}` — 올바른 secret-id로도 실패 |

### AUTH-02. Certificate 로그인 (앱 미구현)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/{cert_mount}/login` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 (Certificate 방식을 쓸 경우) |
| 토큰 / 권한 | 없음. **TLS 연결에서 클라이언트 인증서를 제시**해야 함 |
| 사전 구성 | ADM-01(`cert` 활성화), ADM-06(신뢰 CA와 허용 CN 등록), Vault 리스너 TLS |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| (클라이언트 인증서 + 개인키) | TLS | X.509 | O | ADM-06에 등록한 CA가 서명하고, `allowed_common_names` 등 제약을 만족해야 함. EKU `clientAuth` |
| `name` | body | string | X | ADM-06의 역할 이름. 생략하면 Vault가 등록된 역할을 모두 확인해 인증서에 맞는 역할을 찾음. 역할이 여러 개면 명시 권장 |

**응답** `200` — `auth` (AUTH-01과 같은 구조, `metadata`만 다름)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `auth.metadata.cert_name` | string | 매칭된 역할 이름 |
| `auth.metadata.common_name` | string | 클라이언트 인증서 CN |
| `auth.metadata.serial_number` | string | 인증서 일련번호 |
| `auth.metadata.authority_key_id` / `subject_key_id` | string | 키 식별자 |

```bash
curl --cacert vault-ca.pem -H "X-Vault-Namespace: team-a" --cert sample-app.poc.local.crt --key sample-app.poc.local.key \
  -X POST https://vault.example:8200/v1/auth/cert/login -d '{"name":"sample-app"}'
```
```json
{
  "auth": {
    "client_token": "hvs.CAESIAaxahmoEgwS...(생략)",
    "accessor": "9wg9C2nUl4eFLYa2vg303WZS",
    "lease_duration": 60,
    "metadata": {
      "cert_name": "sample-app",
      "common_name": "sample-app.poc.local",
      "serial_number": "247795789138062924736435856666474249315500792141",
      "authority_key_id": "95:ca:2b:35:3e:8b:dd:c4:38:3e:9a:34:7d:44:12:b9:90:27:c1:66",
      "subject_key_id": "fd:81:a8:71:84:d3:c1:6d:aa:19:cc:3f:12:f9:26:c9:3c:8d:e6:61"
    },
    "policies": ["default", "sample-app-kv"],
    "renewable": true,
    "token_type": "service"
  }
}
```

**오류**

| 상황 | 응답 |
| --- | --- |
| 클라이언트 인증서를 제시하지 않음 | `400 {"errors":["client certificate must be supplied"]}` |
| CN 등 제약 불일치, 신뢰하지 않는 CA | `400 {"errors":["failed to match all constraints for this login certificate"]}` |

> Certificate 방식으로 받은 토큰은 **갱신(TOK-02)할 때도 같은 클라이언트 인증서를 제시**해야 합니다. 상세는 [KV_API_GUIDE.md](KV_API_GUIDE.md) 2.2절.

---

## 5. 토큰 API

### TOK-01. 토큰 정보 조회 (lookup-self)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `GET /v1/auth/token/lookup-self` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 — `TokenManager.executeWithToken`(403을 받았을 때 토큰 유효 여부 확인), `TokenRenewer.start`(periodic 여부 확인) |
| 토큰 / 권한 | 조회할 토큰 / `default` 정책 |

**응답** `200` — `data`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `accessor` | string | 토큰 accessor |
| `display_name` | string | `approle`, `cert-<역할>` 등 로그인 방식 표시 |
| `path` | string | 로그인 경로 (`auth/approle/login`, `auth/cert/login`) |
| `period` | int | periodic 토큰이면 period(초). 아니면 없음 |
| `ttl` / `creation_ttl` | int | 남은 TTL / 처음 TTL |
| `expire_time` / `issue_time` | string | 만료 / 발급 시각 |
| `explicit_max_ttl` | int | 강제 만료 (0 = 없음) |
| `meta` | object | 로그인 메타데이터 (AUTH-01/02의 `auth.metadata`) |
| `policies` | string[] | 정책 |
| `renewable` / `type` / `orphan` / `num_uses` | | |

```json
{"data":{"accessor":"0c3IJOF5BVH8wUocmNE43OGt","display_name":"approle","path":"auth/approle/login","period":60,"ttl":60,"creation_ttl":60,"expire_time":"2026-10-23T15:06:11.003292+09:00","explicit_max_ttl":0,"meta":{"role_name":"sample-app"},"policies":["default","sample-app","sample-app-kv"],"renewable":true,"type":"service"}}
```

**오류**: `403 {"errors":["2 errors occurred:\n\t* permission denied\n\t* invalid token\n\n"]}` — 토큰 만료/폐기

### TOK-02. 토큰 갱신 (renew-self)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/token/renew-self` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `TokenRenewer.renew` (기본 20초마다), 오케스트레이터 (`deliver-secret-id.sh` 전달 전) |
| 토큰 / 권한 | 갱신할 토큰 / `default` 정책 |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `increment` | body | string \| int | X | 연장할 TTL. 생략하면 역할의 `token_ttl` (periodic 토큰은 period) |
| (클라이언트 인증서) | TLS | X.509 | Certificate 토큰만 O | 로그인 때와 같은 인증서. 없으면 `400 client certificate must be supplied` |

**응답** `200` — `auth` (AUTH-01과 같은 구조). 앱은 `auth.lease_duration`으로 만료 시각을 다시 계산하고, `warnings`가 있으면 로그로 남깁니다.

```json
{"warnings":null,"auth":{"client_token":"hvs.CAESIEbrQn1iUBpY...(생략)","accessor":"0c3IJOF5BVH8wUocmNE43OGt","lease_duration":60,"renewable":true,"token_type":"service"}}
```

**오류**: `403` 토큰 무효 → 앱은 재로그인 · `400 client certificate must be supplied` → Certificate 토큰을 인증서 없이 갱신

### TOK-03. 토큰 폐기 (revoke-self)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/token/revoke-self` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `TokenManager` — 재로그인 후 이전 토큰(5초 뒤), 앱 종료 시 현재 토큰 |
| 토큰 / 권한 | 폐기할 토큰 / `default` 정책 |
| 요청 바디 | `{}` |
| 응답 | `204 No Content`. 이후 그 토큰으로 호출하면 `403 ... invalid token` |

---

## 6. secret-id / wrapping API

### SID-01. secret-id 조회 (lookup)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/approle/role/{role}/secret-id/lookup` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `SecretIdLookup.lookup` — 로그인 직후, 새 secret-id 전달 시 |
| 토큰 / 권한 | 앱 토큰 / `auth/approle/role/sample-app/secret-id/lookup`: `update` |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `role` | path | string | O | 역할 이름 |
| `secret_id` | body | string | O | 확인할 secret-id |

**응답** `200`(존재함) — `data` / `204`(존재하지 않음: 잘못된 값, 폐기, 사용 횟수 소진, 만료 후 정리)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `secret_id_accessor` | string | secret-id accessor |
| `expiration_time` | string | 만료 시각. `secret_id_ttl=0`이면 `0001-01-01T00:00:00Z` |
| `creation_time` / `last_updated_time` | string | 생성 / 갱신 시각 |
| `secret_id_ttl` / `secret_id_num_uses` | int | TTL(초) / 사용 가능 횟수 (0 = 무제한) |
| `cidr_list` / `token_bound_cidrs` | string[] | 바인딩된 CIDR |
| `metadata` | object | secret-id 발급 시 붙인 메타데이터 |

```json
{"data":{"cidr_list":[],"creation_time":"2026-10-23T15:05:10.92382+09:00","expiration_time":"2026-10-23T15:10:10.92382+09:00","last_updated_time":"2026-10-23T15:05:10.92382+09:00","metadata":{},"secret_id_accessor":"bc89d777-7b2b-8695-80c8-d7e6d127328d","secret_id_num_uses":0,"secret_id_ttl":300,"token_bound_cidrs":[]}}
```

**오류**: `403` 토큰 무효 또는 정책에 lookup 권한 없음

### SID-02. secret-id 발급

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/approle/role/{role}/secret-id` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 오케스트레이터 `scripts/deliver-secret-id.sh` (앱은 권한 없음) |
| 토큰 / 권한 | 오케스트레이터 토큰 / `auth/approle/role/sample-app/secret-id`: `update` |

**요청 파라미터**

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `X-Vault-Wrap-TTL` | header | duration | X | 지정하면 응답을 wrapping 토큰으로 받음 (`ORCH_WRAPPED=true`, 기본 `5m`) |
| `metadata` / `cidr_list` / `ttl` / `num_uses` | body | | X | 이 프로젝트에서는 사용하지 않음 (역할 기본값 사용) |

**응답** `200`

| 경우 | 필드 | 설명 |
| --- | --- | --- |
| 원문 | `data.secret_id`, `data.secret_id_accessor`, `data.secret_id_ttl`, `data.secret_id_num_uses` | 오케스트레이터가 `secret_id`를 파일에 씀 |
| wrapping | `wrap_info.token`, `wrap_info.ttl`, `wrap_info.creation_path`, `wrap_info.wrapped_accessor` | 오케스트레이터가 `wrap_info.token`을 파일에 씀 (원문을 보지 않음) |

```json
{"data":{"secret_id":"2b4c4263-cf29-331f-89a5-f8b79d251dbe","secret_id_accessor":"bc89d777-7b2b-8695-80c8-d7e6d127328d","secret_id_num_uses":0,"secret_id_ttl":300}}
```
```json
{"data":null,"wrap_info":{"token":"hvs.CAESIBsCVhgz8uSY...(생략)","accessor":"OgjQ3HzXlavzsIRAVdMm6AVp","ttl":300,"creation_time":"2026-10-23T15:05:10.94211+09:00","creation_path":"auth/approle/role/sample-app/secret-id","wrapped_accessor":"c5d7fc2a-9cf4-f11d-8f51-22cbf49f9eb0"}}
```

### WRP-01. wrapping 토큰 확인 (lookup)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/sys/wrapping/lookup` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `SecretIdFileWatcher` (`VAULT_SECRET_ID_WRAPPED=true`) |
| 토큰 / 권한 | 없음. wrapping 토큰을 소모하지 않음 |

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `token` | body | string | O | wrapping 토큰 |

**응답** `200` — `data.creation_path`, `data.creation_time`, `data.creation_ttl`. 앱은 `creation_path`가 `auth/{approle_mount}/role/{role}/secret-id`인지 확인하고 다르면 거부합니다.

```json
{"data":{"creation_path":"auth/approle/role/sample-app/secret-id","creation_time":"2026-10-23T15:05:10.94211+09:00","creation_ttl":300}}
```

### WRP-02. wrapping 토큰 풀기 (unwrap)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/sys/wrapping/unwrap` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `SecretIdFileWatcher` |
| 토큰 / 권한 | **wrapping 토큰 자체**를 `X-Vault-Token`으로 사용 / 없음 |
| 요청 바디 | `{}` |

**응답** `200` — wrapping 전의 원래 응답(SID-02 원문과 같은 `data`)

```json
{"data":{"secret_id":"42c117cd-4975-a289-096b-947d0d6ff74c","secret_id_accessor":"c5d7fc2a-9cf4-f11d-8f51-22cbf49f9eb0","secret_id_num_uses":0,"secret_id_ttl":300}}
```

**오류**: `400 {"errors":["wrapping token is not valid or does not exist"]}` — 이미 unwrap됨(1회용) 또는 만료

---

## 7. KV v2 API (앱 구현분)

KV v2의 전체 작업(업데이트, 부분 변경, 삭제, 복구, 영구 삭제, 메타데이터, 목록)은 [KV_API_GUIDE.md](KV_API_GUIDE.md)에 정리했습니다. 여기는 현재 앱이 호출하는 두 API만 둡니다.

### KV-01. 시크릿 조회

| 항목 | 내용 |
| --- | --- |
| Method / Path | `GET /v1/{mount}/data/{path}` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `KvSecretClient.read` |
| 토큰 / 권한 | 앱 토큰 / `secret/data/sample-app/*`: `read` |

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `path` | path | string | O | 시크릿 경로 |
| `version` | query | int | X | 조회할 버전. 생략하면 최신 (앱은 사용하지 않음) |

**응답** `200`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `data.data` | object | 시크릿 key/value |
| `data.metadata.version` | int | 버전 |
| `data.metadata.created_time` | string | 이 버전 생성 시각 |
| `data.metadata.custom_metadata` | object \| null | 사용자 메타데이터 (버전과 무관하게 현재 값) |
| `data.metadata.deletion_time` / `destroyed` | string / boolean | 삭제 시각 / 영구 삭제 여부 |

**오류**: `404` 없음 또는 삭제된 버전 · `403` 정책 밖 경로

### KV-02. 시크릿 저장 (생성 / 새 버전)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/{mount}/data/{path}` (`PUT` 동일) |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 호출 주체 | 앱 `KvSecretClient.write`, 관리자 셋업 스크립트(`vault kv put`) |
| 토큰 / 권한 | 앱 토큰 / `create`(새 경로), `update`(기존 경로) |

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `data` | body | object | O | 저장할 key/value (전체를 새 버전으로 저장) |
| `options.cas` | body | int | X | check-and-set. `0`=없을 때만 생성, `N`=현재 버전이 N일 때만 저장 (앱은 사용하지 않음) |

**응답** `200` — `data.version`, `data.created_time`, `data.custom_metadata`, `data.deletion_time`, `data.destroyed`

```json
{"data":{"created_time":"2026-10-23T06:05:10.804547Z","custom_metadata":null,"deletion_time":"","destroyed":false,"version":1}}
```

**오류**: `403` 정책 밖 경로 · `400 check-and-set parameter did not match the current version`

---

## 8. 관리 API (scripts/setup-vault.sh)

관리자가 root 토큰으로 한 번 실행하는 구성 API입니다. 스크립트는 `vault` CLI로 호출하며, 괄호 안이 대응하는 CLI 명령입니다.

### ADM-01. 인증 방식 활성화 (`vault auth enable approle` / `cert`)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/sys/auth/{mount}` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 바디 | `{"type":"approle"}` 또는 `{"type":"cert"}` |
| 응답 | `204` · 이미 있으면 `400 path is already in use` |

### ADM-02. 정책 작성 (`vault policy write`)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `PUT /v1/sys/policies/acl/{name}` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 바디 | `{"policy": "<HCL 원문>"}` |
| 응답 | `204` |

이 프로젝트의 정책:

| 정책 | 대상 | 내용 |
| --- | --- | --- |
| `sample-app` | 앱 | `secret/data/sample-app/*`: create, read, update · `auth/approle/role/sample-app/secret-id/lookup`: update |
| `sample-app-orchestrator` | 오케스트레이터 | `auth/approle/role/sample-app/secret-id`: update |
| `sample-app-kv` (선택) | 앱 | KV 전체 작업 + 메타데이터. 원문은 [KV_API_GUIDE.md](KV_API_GUIDE.md) 2.4절 |

### ADM-03. AppRole 역할 생성 (`vault write auth/approle/role/sample-app ...`)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/approle/role/{role}` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 응답 | `204` |

| 파라미터 | 값 | 설명 |
| --- | --- | --- |
| `token_policies` | `sample-app` | 로그인 토큰에 붙일 정책 |
| `token_period` | `1m` (운영 `1h` 등) | periodic 토큰. period 안에 갱신하면 TTL이 다시 채워짐 |
| `token_max_ttl` / `token_explicit_max_ttl` | `0` | periodic 토큰을 강제 만료시키지 않도록 비움 |
| `secret_id_ttl` | `5m` | secret-id 수명 |
| `secret_id_num_uses` | `0` | 사용 횟수 무제한 |

### ADM-04. role-id 조회 (`vault read auth/approle/role/sample-app/role-id`)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `GET /v1/auth/approle/role/{role}/role-id` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 응답 | `200 {"data":{"role_id":"3f731e94-7a80-b363-835d-39e9f335713c"}}` |

### ADM-05. 오케스트레이터 토큰 발급 (`vault token create -orphan -period=24h -policy=...`)

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/token/create-orphan` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 바디 | `{"policies":["sample-app-orchestrator"],"period":"24h"}` |
| 응답 | `200` — `auth.client_token`(→ `.vault/orchestrator.env`), `auth.lease_duration=86400`, `auth.orphan=true` |

### ADM-06. Certificate 역할 등록 (`vault write auth/cert/certs/sample-app ...`)

Certificate 인증을 쓸 때만 필요합니다.

| 항목 | 내용 |
| --- | --- |
| Method / Path | `POST /v1/auth/cert/certs/{name}` |
| 네임스페이스 | `X-Vault-Namespace: <ns>` (네임스페이스 사용 시, 1.5절) |
| 응답 | `204` |

| 파라미터 | 예 | 설명 |
| --- | --- | --- |
| `certificate` | PEM | 신뢰할 CA 인증서. 이 CA가 서명한 클라이언트 인증서만 로그인 |
| `allowed_common_names` | `["sample-app.poc.local"]` | 허용 CN (글롭 가능). `allowed_dns_sans`, `allowed_organizational_units` 등도 있음 |
| `display_name` | `sample-app` | 토큰 `display_name`이 `cert-sample-app`이 됨 |
| `token_policies` | `["sample-app-kv"]` | 토큰 정책 |
| `token_period` | `1m` | periodic 토큰 |
| `ocsp_enabled` | `false` | OCSP 폐기 확인 (필요 시) |
