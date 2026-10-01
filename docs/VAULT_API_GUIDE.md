# Vault 연동 코드 가이드 (Java 함수 ↔ Vault HTTP API)

이 문서는 샘플 앱의 Java 함수 각각이 **어떤 Vault HTTP API를 호출하는지**를 정리합니다. 함수마다 request/response도 함께 적었습니다.

- 모든 request/response 예시는 Vault OSS 2.1.1 dev 서버에서 실제로 받은 값입니다. 토큰과 secret-id 값은 일부만 남기고 줄였습니다.
- Vault HTTP API 원문 문서: <https://developer.hashicorp.com/vault/api-docs>

---

## 1. 사용한 라이브러리

이 샘플은 **Vault 전용 Java 라이브러리(SDK)를 쓰지 않습니다.** JDK에 기본으로 들어 있는 HTTP 클라이언트로 Vault HTTP API를 직접 호출합니다.

| 라이브러리 | 버전 | 용도 | 사용 위치 |
| --- | --- | --- | --- |
| `java.net.http.HttpClient` (JDK 내장) | Java 17 | Vault HTTP API 호출 | `VaultHttpClient` |
| `com.sun.net.httpserver.HttpServer` (JDK 내장) | Java 17 | 앱의 KV API 서버 | `KvApiServer` |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 | 요청/응답 JSON 변환 | `VaultHttpClient`, `KvSecretClient`, `KvApiServer` |
| `org.slf4j:slf4j-api` + `slf4j-simple` | 2.0.13 | 로그 출력 | 전체 |

### 왜 SDK를 쓰지 않았나
- **흐름이 그대로 보입니다.** 어떤 API를 어떤 토큰으로 호출하는지가 코드에 드러나므로, POC에서 인증 흐름을 설명하고 검증하기 좋습니다.
- **의존성이 적습니다.** Jackson과 SLF4J만 있으면 됩니다.
- **Vault HTTP API는 안정적인 공개 계약입니다.** CLI와 모든 SDK가 결국 같은 API를 호출합니다.

### 실제 프로젝트에서 쓸 수 있는 SDK
운영 프로젝트에서는 아래 SDK를 검토할 수 있습니다. 이름이 다를 뿐, 이 문서의 HTTP API를 똑같이 호출합니다.

| SDK | 특징 | 이 샘플과 대응되는 기능 |
| --- | --- | --- |
| **Spring Vault** (`spring-vault-core`) | Spring 환경 표준. AppRole 인증, 토큰 자동 갱신(`LifecycleAwareSessionManager`), KV 템플릿 제공 | `AppRoleAuthentication`, `VaultTemplate.opsForKeyValue()` |
| **vault-java-driver** (`io.github.jopenlibs:vault-java-driver`) | 의존성 없는 경량 드라이버. 원래 BetterCloud 프로젝트였고 현재는 커뮤니티 fork가 유지 | `vault.auth().loginByAppRole()`, `vault.logical().read()` |

---

## 2. 공통 사항

### 2.1 `VaultHttpClient` — 모든 Vault 호출이 거치는 저수준 클라이언트

| 메서드 | 설명 |
| --- | --- |
| `JsonNode get(String path, String token)` | `GET {VAULT_ADDR}/v1/{path}` |
| `JsonNode post(String path, String token, Map<String, ?> body)` | `POST {VAULT_ADDR}/v1/{path}`. body가 `null`이면 `{}`를 보냄 |

공통 동작:

| 항목 | 내용 |
| --- | --- |
| URL | `{VAULT_ADDR}/v1/` + path. 예: `path="auth/approle/login"` → `http://127.0.0.1:8200/v1/auth/approle/login` |
| `X-Vault-Token` 헤더 | `token` 인자가 `null`이 아니면 추가 (로그인/unwrap lookup은 `null`) |
| `X-Vault-Namespace` 헤더 | `VAULT_NAMESPACE`가 설정된 경우만 추가 (Enterprise 전용) |
| 타임아웃 | 연결 5초, 요청 10초 |
| 2xx 응답 | 응답 JSON을 `JsonNode`로 반환. 204(바디 없음)는 `MissingNode` 반환 |
| 2xx 외 응답 | `VaultException(statusCode, message)`을 던짐 |
| 네트워크 오류 | `VaultException(statusCode=-1)`을 던짐 |

`VaultHttpClient`는 스레드 세이프하므로 인스턴스 하나를 모든 스레드가 공유합니다. 토큰은 보관하지 않고, 호출할 때마다 인자로 받습니다.

### 2.2 Vault 응답 공통 구조

모든 성공 응답은 아래 형태입니다. API에 따라 `data` 또는 `auth` 중 하나에 결과가 들어갑니다.

```json
{
  "request_id": "5363de9c-4251-8eeb-bd5b-cb320fb76138",
  "lease_id": "",
  "renewable": false,
  "lease_duration": 0,
  "data": { ... },        // 일반 API 결과 (KV 조회, secret-id 발급 등)
  "auth": { ... },        // 인증 API 결과 (로그인, 토큰 갱신)
  "wrap_info": null,      // response wrapping 을 요청한 경우 여기에 wrapping 토큰
  "warnings": null,       // 경고 (예: TTL 이 max_ttl 에 막혀 줄어든 경우)
  "mount_type": "kv"
}
```

### 2.3 Vault 오류 응답과 `VaultException`

오류 응답은 `{"errors": [...]}` 형태입니다. 앱은 HTTP 상태 코드를 `VaultException.statusCode()`에 담습니다.

| 상태 | 의미 | 실제 응답 예 |
| --- | --- | --- |
| 400 | 잘못된 요청 (틀린 secret-id, 이미 사용한 wrapping 토큰 등) | `{"errors":["invalid role or secret ID"]}` |
| 403 | **AppRole 로그인 잠김** (user lockout: 로그인 연속 실패) | `{"errors":["permission denied"]}` |
| 403 | **토큰 무효** (만료/폐기) | `{"errors":["2 errors occurred:\n\t* permission denied\n\t* invalid token\n\n"]}` |
| 403 | **정책상 권한 없음** (토큰은 유효) | `{"errors":["1 error occurred:\n\t* permission denied\n\n"]}` |
| 404 | 대상 없음 (없는 KV 경로) | `{"errors":[]}` |
| 503 | (앱 내부) 지금은 로그인할 수 없음 — Vault를 호출하지 않고 앱이 직접 던짐 | `LoginUnavailableException` |

> **403 구분**: Vault는 토큰이 무효인 경우와 권한이 없는 경우 모두 403을 줍니다. 앱은 오류 메시지 문자열에 의존하지 않고, `lookup-self`로 토큰이 유효한지 다시 확인해서 둘을 구분합니다. (4.3절 `executeWithToken` 참고)

`VaultException` 메서드:

| 메서드 | 설명 |
| --- | --- |
| `int statusCode()` | HTTP 상태 코드. 응답을 받지 못했으면 `-1` |
| `boolean isPermissionDenied()` | `statusCode == 403` |

`LoginUnavailableException`(`VaultException`의 하위 클래스, `statusCode=503`)은 지금 로그인할 수 없는 상태를 나타냅니다. 앱 API에서는 `503`으로 응답합니다. (4.8절 참고)

---

## 3. AppRole 로그인 — `AppRoleAuthenticator`

### 3.1 `VaultToken login(String roleId, String secretId)`

role-id와 secret-id로 로그인해 클라이언트 토큰을 받습니다. 상태가 없는 클래스라 여러 스레드에서 동시에 호출해도 됩니다.

**Vault API**: [`POST /v1/auth/approle/login`](https://developer.hashicorp.com/vault/api-docs/auth/approle#login-with-approle) · 토큰 불필요

**Java**
```java
AppRoleAuthenticator auth = new AppRoleAuthenticator(client, "approle");
VaultToken token = auth.login(roleId, secretId);
```

**Request**
```http
POST /v1/auth/approle/login
Content-Type: application/json

{
  "role_id": "b63b419d-2764-a444-b62a-2c1d6e2883f4",
  "secret_id": "ffbb9194-d22e-37d1-d55b-42c321578adf"
}
```

**Response** `200`
```json
{
  "request_id": "5363de9c-4251-8eeb-bd5b-cb320fb76138",
  "data": null,
  "auth": {
    "client_token": "hvs.CAESIL99EM7OSEd9...(생략)",
    "accessor": "HoVn8GIc8Bg3V645qRl0F1ZZ",
    "policies": ["default", "sample-app"],
    "token_policies": ["default", "sample-app"],
    "metadata": { "role_name": "sample-app" },
    "lease_duration": 60,
    "renewable": true,
    "entity_id": "6cea2380-7782-fe5f-ac92-bd2bf1acf63b",
    "token_type": "service",
    "orphan": true,
    "num_uses": 0
  }
}
```

**Java에서 사용하는 필드 → `VaultToken`**

| 응답 필드 | `VaultToken` 필드 | 용도 |
| --- | --- | --- |
| `auth.client_token` | `token` | 이후 모든 요청의 `X-Vault-Token` |
| `auth.accessor` | `accessor` | 로그에 토큰 대신 남기는 식별자 |
| `auth.lease_duration` | `leaseDurationSec` | 만료 시각 계산 (`issuedAt + leaseDurationSec`) |
| `auth.renewable` | `renewable` | `renew-self` 가능 여부 |
| (응답 수신 시각) | `issuedAt` | 만료 시각 계산 기준 |

**오류**

| 상황 | 응답 |
| --- | --- |
| secret-id가 틀렸거나 만료/폐기됨 | `400 {"errors":["invalid role or secret ID"]}` |
| role-id가 잠김 (로그인 연속 실패, 기본 5회 → 15분) | `403 {"errors":["permission denied"]}` — **올바른 secret-id로도** 실패 |

> **User lockout**: Vault 1.13부터 AppRole 로그인이 연속으로 실패하면 role-id가 잠깁니다. 기본값은 5회 실패에 15분 잠금입니다.
> - 확인: `vault read sys/locked-users`
> - 해제: `vault write -f sys/locked-users/<mount_accessor>/unlock/<role_id>`
> - 설정: `vault auth tune -user-lockout-threshold=… -user-lockout-duration=… approle/`
>
> 앱은 실패할 것이 확실한 로그인을 반복하지 않도록 만들었습니다(4.8절).

---

## 4. 토큰 관리 — `TokenManager`

토큰과 secret-id를 여러 스레드가 공유할 수 있게 관리하는 핵심 클래스입니다.

- **기본 구조**: 한 번 로그인해서 받은 periodic 토큰을 `TokenRenewer`가 계속 갱신하며 씁니다. secret-id로 로그인하는 것은 **앱 시작**과 **장애 복구**(토큰 강제 폐기, 갱신을 놓쳐 만료) 때뿐입니다.
- 새 secret-id를 전달받아도 로그인하지 않습니다. secret-id 조회(lookup)로 확인한 뒤 보관만 합니다.
- 현재 토큰과 secret-id는 `AtomicReference`에 담아 둡니다. 토큰을 읽을 때는 락을 잡지 않습니다.
- 로그인과 secret-id 교체는 `ReentrantLock`으로 한 번에 한 스레드만 수행합니다.

| 상황 | Vault 호출 | 토큰 |
| --- | --- | --- |
| 평소 (API 요청) | KV API만 | 유지 |
| 주기 갱신 | `renew-self` | 유지 (TTL만 다시 채움) |
| 새 secret-id 전달 | `secret-id/lookup` | 유지 |
| 앱 시작 | `login` → `secret-id/lookup` | 새로 받음 |
| 장애 복구 | `login` (secret-id가 유효할 때만) → `secret-id/lookup` | 새로 받음 |

### 4.1 `void initialize()`

앱을 시작할 때 최초 로그인을 합니다. 실패하면 예외를 던지고, 앱 기동이 중단됩니다.

로그인하기 전에는 토큰이 없어서 secret-id를 조회할 수 없습니다. 그래서 로그인 자체를 검증으로 삼고, 로그인한 뒤 받은 토큰으로 secret-id를 조회해 만료 시각을 기록해 둡니다(4.9절).

**Vault API**: `POST /v1/auth/approle/login` (3.1절과 동일) → `POST /v1/auth/approle/role/{role}/secret-id/lookup` (4.9절)

### 4.2 `VaultToken getToken()` / `VaultToken currentToken()`

| 메서드 | 설명 | Vault 호출 |
| --- | --- | --- |
| `getToken()` | 요청 스레드가 쓸 토큰을 반환합니다. 만료가 10초 안으로 다가왔으면 먼저 재로그인합니다. | 평소에는 없음. 만료가 임박하면 `login` |
| `currentToken()` | 현재 토큰을 그대로 반환합니다. 재로그인하지 않습니다. 앱이 종료된 뒤에는 `null`입니다. | 없음 |

### 4.3 `<T> T executeWithToken(TokenCall<T> call)`

토큰이 필요한 모든 Vault 호출(KV 조회/저장 등)은 이 메서드를 거칩니다. 403을 받으면 토큰이 유효한지 확인한 뒤, 무효일 때만 재로그인하고 한 번 재시도합니다.

**Java**
```java
JsonNode res = tokenManager.executeWithToken(t -> client.get("secret/data/sample-app/config", t.token()));
```

**동작 순서**
```
call(token) ── 성공 ──────────────────────────────▶ 결과 반환
     │
     └ 403 ─▶ GET auth/token/lookup-self
                 ├ 200 (토큰 유효 = 정책 거부) ─▶ 403 그대로 던짐 (재로그인 안 함)
                 └ 403 (토큰 무효) ─▶ refreshIfStale() 로 재로그인 ─▶ call(새 토큰) 1회 재시도
```

**Vault API**: [`GET /v1/auth/token/lookup-self`](https://developer.hashicorp.com/vault/api-docs/auth/token#lookup-a-token-self) · 필요한 권한은 `default` 정책에 포함

**Request**
```http
GET /v1/auth/token/lookup-self
X-Vault-Token: hvs.CAESIL99EM7OSEd9...(생략)
```

**Response** `200` (토큰이 유효한 경우)
```json
{
  "data": {
    "accessor": "HoVn8GIc8Bg3V645qRl0F1ZZ",
    "creation_time": 1790815917,
    "creation_ttl": 60,
    "display_name": "approle",
    "expire_time": "2026-10-01T00:52:57.39924018Z",
    "explicit_max_ttl": 0,
    "id": "hvs.CAESIL99EM7OSEd9...(생략)",
    "issue_time": "2026-10-01T00:51:57.399242471Z",
    "meta": { "role_name": "sample-app" },
    "num_uses": 0,
    "orphan": true,
    "path": "auth/approle/login",
    "policies": ["default", "sample-app"],
    "renewable": true,
    "ttl": 60,
    "type": "service"
  }
}
```

**Response** `403` (토큰이 만료되었거나 폐기된 경우)
```json
{"errors":["2 errors occurred:\n\t* permission denied\n\t* invalid token\n\n"]}
```

> 앱은 응답의 성공(200)/실패 여부만 봅니다. 응답 내용은 사용하지 않습니다.

### 4.4 `VaultToken refreshIfStale(VaultToken stale)`

장애 복구용입니다. `stale` 토큰이 아직 현재 토큰일 때만 재로그인합니다.
- 여러 스레드가 같은 토큰으로 403을 받고 동시에 호출하더라도, 실제 로그인은 첫 스레드만 합니다.
- 나머지 스레드는 첫 스레드가 받아 둔 새 토큰을 가져갑니다(single-flight).

**Vault API**: `POST /v1/auth/approle/login` (보관 중인 secret-id 사용) → 성공하면 secret-id 조회

토큰이 죽은 상태라서 이 시점에는 secret-id를 조회할 수 없습니다. 그래서 **미리 조회해 둔 만료 시각**으로 판단합니다. secret-id가 만료됐거나 거부된 적이 있으면(4.8절) Vault를 호출하지 않고 `LoginUnavailableException`을 바로 던집니다. 로그인 결과에 따른 처리는 다음과 같습니다.

| 로그인 결과 | 처리 |
| --- | --- |
| 200 | 토큰 교체 |
| 400 (secret-id 거부) | 현재 secret-id를 **거부됨**으로 표시하고 `LoginUnavailableException`을 던집니다. 이후 새 secret-id가 올 때까지 로그인하지 않습니다. |
| 403 (잠김) | 60초 동안 로그인을 멈추고 `LoginUnavailableException`을 던집니다. |

### 4.5 `boolean applyRenewal(VaultToken before, VaultToken renewed)`

`TokenRenewer`가 연장한 토큰을 반영합니다(compare-and-set). 연장하는 사이에 다른 스레드가 재로그인해서 토큰이 바뀌었다면 `false`를 반환하고, 연장 결과는 버립니다. Vault는 호출하지 않습니다.

### 4.6 `void switchSecretId(SecretIdCredential newCredential)`

오케스트레이터가 새 secret-id를 전달하면 호출됩니다. 토큰이 살아 있는지에 따라 처리가 달라집니다.

**① 토큰이 살아 있을 때 (평소)** — 로그인하지 않습니다.

**Vault API**: `POST /v1/auth/approle/role/{role}/secret-id/lookup` (4.9절)

| 조회 결과 | 처리 |
| --- | --- |
| 200, `expiration_time`이 미래 | 만료 시각을 기록하고 보관합니다. "거부됨" 표시를 해제합니다. **토큰은 그대로입니다.** |
| 200, `expiration_time`이 과거 | 예외를 던집니다. 기존 secret-id를 유지합니다 (이미 만료된 secret-id). |
| 204 | 예외를 던집니다. 기존 secret-id를 유지합니다 (잘못된 값 / 폐기됨 / 사용 횟수 소진). |
| 403 + 토큰은 유효 | 예외를 던집니다. 앱 정책에 `secret-id/lookup` 권한이 없는 설정 오류입니다. |
| 403 + 토큰 무효 | ②로 넘어갑니다. |

**② 토큰이 없거나 죽었을 때 (장애 중)** — 조회할 토큰이 없으므로, 새 secret-id로 로그인해서 검증과 복구를 함께 합니다.

**Vault API**: `POST /v1/auth/approle/login` (새 secret-id 사용) → 성공하면 secret-id 조회

| 로그인 결과 | 처리 |
| --- | --- |
| 200 | secret-id와 토큰을 교체하고 "거부됨" 표시를 해제합니다 (복구). |
| 400 | 예외를 던집니다. 기존 secret-id를 유지합니다 (잘못된 secret-id가 전달된 경우). |
| 403 (잠김) 또는 이미 잠금 대기 중 | 검증할 수 없으므로 새 secret-id를 **검증 없이 보관**합니다. 잠금 대기가 끝난 뒤 재로그인할 때 사용합니다. 버리면 잠금이 풀린 뒤에도 쓸 secret-id가 없어 회복할 수 없기 때문입니다. |

### 4.7 `void revokeCurrent()` 및 이전 토큰 지연 폐기

| 시점 | 동작 |
| --- | --- |
| 재로그인으로 토큰이 바뀐 직후 | 이전 토큰을 `OLD_TOKEN_REVOKE_GRACE_SEC`(5초) 뒤에 폐기합니다. 그 토큰으로 처리 중이던 요청을 보호하기 위해서입니다. |
| 앱 종료 (`revokeCurrent()`) | 현재 토큰을 즉시 폐기합니다. |

**Vault API**: [`POST /v1/auth/token/revoke-self`](https://developer.hashicorp.com/vault/api-docs/auth/token#revoke-a-token-self) · 필요한 권한은 `default` 정책에 포함

**Request**
```http
POST /v1/auth/token/revoke-self
X-Vault-Token: hvs.CAESIL99EM7OSEd9...(생략)
Content-Type: application/json

{}
```

**Response** `204 No Content` (바디 없음)

폐기된 뒤 그 토큰으로 호출하면 `403 ... invalid token`이 납니다.

### 4.8 로그인 실패 보호 / `String loginBlockedReason()`

**secret-id가 유효할 때만 로그인**하기 위한 관문입니다. 아래 상태에서는 Vault를 호출하지 않고 로그인을 막습니다. 실패할 로그인을 반복하면 실패 횟수가 쌓여 role-id가 잠기고, 그러면 올바른 secret-id가 와도 회복할 수 없기 때문입니다.

| 상태 | 들어가는 조건 | 풀리는 조건 | `loginBlockedReason()` |
| --- | --- | --- | --- |
| secret-id 만료 | 조회로 기록해 둔 `expiration_time`이 지남 | 새 secret-id가 전달됨 | `SECRET_ID_EXPIRED` |
| secret-id 거부됨 | 재로그인이 `400`, 또는 만료된 상태에서 로그인이 필요해짐 | 새 secret-id가 전달됨 | `SECRET_ID_REJECTED` |
| 잠금 대기 | 로그인이 `403` | 60초(`LOCKOUT_BACKOFF`) 경과 | `LOGIN_LOCKED_OUT (until …)` |

평소에는 로그인하지 않으므로, 이 값이 있어도 당장 장애는 아닙니다. 다만 **토큰을 잃으면 복구할 수 없는 상태**라는 뜻이므로 `/health`로 모니터링해야 합니다. 이 상태에서 토큰이 죽으면, 토큰이 필요한 호출은 `LoginUnavailableException`(503)으로 즉시 실패합니다. `TokenRenewer`는 WARN 로그(`토큰 갱신 보류: …`)만 남깁니다.

> Vault는 만료된 secret-id를 즉시 지우지 않고 주기적으로 정리합니다. 그래서 만료 직후 잠깐은 조회가 200으로 오고 로그인도 성공할 수 있습니다. 앱은 `expiration_time`을 직접 비교해서 Vault보다 엄격하게 판단합니다.

### 4.9 secret-id 조회 — `SecretIdLookup.lookup(SecretIdCredential cred, String token)`

로그인하지 않고 secret-id가 유효한지, 언제 만료되는지 확인합니다. 이 권한으로는 secret-id를 만들 수 없고, 이미 가지고 있는 secret-id의 정보만 볼 수 있습니다.

**Vault API**: [`POST /v1/auth/approle/role/{role_name}/secret-id/lookup`](https://developer.hashicorp.com/vault/api-docs/auth/approle#read-approle-secret-id) · 앱 정책에 `auth/approle/role/sample-app/secret-id/lookup`의 `update` 권한 필요

**Java**
```java
Optional<SecretIdCredential> found = secretIdLookup.lookup(cred, token.token());
found.get().expiresAt();   // 2026-10-01T01:33:21.936776635Z
found.get().isExpired();   // false
```

**Request**
```http
POST /v1/auth/approle/role/sample-app/secret-id/lookup
X-Vault-Token: (앱 토큰)
Content-Type: application/json

{ "secret_id": "ffbb9194-d22e-37d1-d55b-42c321578adf" }
```

**Response** `200` — Vault에 존재함
```json
{
  "data": {
    "cidr_list": [],
    "creation_time": "2026-10-01T01:28:21.936776635Z",
    "expiration_time": "2026-10-01T01:33:21.936776635Z",
    "last_updated_time": "2026-10-01T01:28:21.936776635Z",
    "metadata": {},
    "secret_id_accessor": "aee423a8-b631-afa0-1fab-9de365c51c41",
    "secret_id_num_uses": 0,
    "secret_id_ttl": 300,
    "token_bound_cidrs": []
  },
  "mount_type": "approle"
}
```

**Response** `204 No Content` — 존재하지 않음 (잘못된 값 / 폐기됨 / 사용 횟수 소진 / 만료 후 정리됨)

**Java에서 사용하는 필드 → `SecretIdCredential`**

| 응답 필드 | `SecretIdCredential` 필드 | 비고 |
| --- | --- | --- |
| `data.secret_id_accessor` | `accessor` | |
| `data.expiration_time` | `expiresAt` | `secret_id_ttl=0`이면 Vault가 `0001-01-01T00:00:00Z`로 주며, 앱은 `null`(만료 없음)로 저장 |
| (조회 성공) | `verified = true` | |

---

## 5. 토큰 갱신 — `TokenRenewer`

role에 `token_period`를 설정하면 로그인으로 받은 토큰이 **periodic 토큰**이 됩니다. period 안에 renew하기만 하면 TTL이 매번 period로 다시 채워지고, max_ttl 제한도 받지 않습니다. 그래서 한 번 받은 토큰을 재로그인 없이 계속 쓸 수 있습니다.

| role 설정 (setup 스크립트) | 값 | 의미 |
| --- | --- | --- |
| `token_period` | `1m` (dev) / 운영은 `1h` 등 | 갱신 한 번에 다시 채워지는 TTL |
| `token_max_ttl` | `0` | periodic 토큰에는 적용되지 않음 |
| `token_explicit_max_ttl` | `0` | 설정하면 periodic 토큰도 그 시간에 강제 만료되므로 비워 둠 |

### 5.1 `void start()`

먼저 `lookup-self`로 토큰이 periodic인지 확인합니다. 아니면 WARN 로그를 남깁니다. 그다음 `TOKEN_RENEW_INTERVAL_SEC`(기본 20초)마다 `renew()`를 실행하도록 예약합니다. 이전 실행이 끝난 시점부터 간격을 셉니다(`scheduleWithFixedDelay`).

**Vault API**: `GET /v1/auth/token/lookup-self` (4.3절과 같은 API). `data.period`를 봅니다.

```json
{ "data": { "period": 60, "ttl": 59, "creation_ttl": 60, "explicit_max_ttl": 0, "renewable": true, "...": "..." } }
```

| `data.period` | 로그 |
| --- | --- |
| `> 0` | `periodic 토큰 확인: period=60s (갱신만 계속하면 재로그인 없이 사용)` |
| 없음 / `0` | `periodic 토큰이 아닙니다. token_max_ttl 에 도달할 때마다 secret-id 로 재로그인합니다.` (WARN) |

### 5.2 `void renew()`

현재 토큰의 TTL을 연장합니다.

**Vault API**: [`POST /v1/auth/token/renew-self`](https://developer.hashicorp.com/vault/api-docs/auth/token#renew-a-token-self) · 필요한 권한은 `default` 정책에 포함

**Request**
```http
POST /v1/auth/token/renew-self
X-Vault-Token: hvs.CAESIL99EM7OSEd9...(생략)
Content-Type: application/json

{}
```
`increment`를 생략하면 role의 `token_ttl`(60초)만큼 연장됩니다.

**Response** `200`
```json
{
  "data": null,
  "warnings": null,
  "auth": {
    "client_token": "hvs.CAESIL99EM7OSEd9...(생략)",
    "accessor": "HoVn8GIc8Bg3V645qRl0F1ZZ",
    "policies": ["default", "sample-app"],
    "lease_duration": 60,
    "renewable": true,
    "token_type": "service"
  }
}
```

**Response** `200` — `token_max_ttl`에 가까워서 TTL이 줄어든 경우
```json
{
  "warnings": [
    "TTL of \"1m\" exceeded the effective max_ttl of \"50s\"; TTL value is capped accordingly"
  ],
  "auth": {
    "lease_duration": 50,
    "...": "..."
  }
}
```

**응답에 따른 Java 처리**

| 조건 | 처리 |
| --- | --- |
| `auth.lease_duration > 주기 + 10초` (periodic 토큰은 항상 여기) | `withRenewedLease(lease)`로 새 `VaultToken`을 만들어 `applyRenewal()`로 반영 |
| `auth.lease_duration <= 주기 + 10초` (periodic이 아니어서 max_ttl 근접) | (안전장치) 연장하지 않고 `refreshIfStale()`로 재로그인 |
| `warnings` 있음 | INFO 로그로 출력 |
| `403` (토큰 무효) | `refreshIfStale()`로 재로그인 |
| `LoginUnavailableException` | Vault를 호출하지 않고 `토큰 갱신 보류` WARN 로그만 남김 (4.8절) |
| `renewable=false` 토큰 | renew를 호출하지 않음. 만료가 임박하면 재로그인 |

> periodic 토큰은 renew할 때마다 `lease_duration`이 period(60)로 다시 채워지고 `warnings`도 없습니다. 실제 응답: `{"warnings":null,"auth":{"lease_duration":60,"renewable":true,"token_type":"service"}}`. 위의 "TTL이 줄어든 경우" 응답은 periodic이 아닌 토큰에서만 나옵니다.

---

## 6. secret-id 수신 — `SecretIdFileWatcher`

오케스트레이터가 전달한 secret-id 파일을 감시합니다. 앱은 이 파일을 **읽기만** 하고, secret-id를 직접 발급하지는 않습니다.

### 6.1 `SecretIdCredential loadInitial()`

앱을 시작할 때 파일을 읽어 최초 secret-id를 얻습니다. 파일이 없거나 비어 있거나, wrapping 토큰이 무효이면 예외를 던집니다.

| `VAULT_SECRET_ID_WRAPPED` | Vault 호출 |
| --- | --- |
| `false` (기본) | 없음. 파일 내용을 secret-id로 그대로 사용 |
| `true` | `sys/wrapping/lookup` → `sys/wrapping/unwrap` (6.3절) |

### 6.2 `void start(TokenManager tokenManager)`

`SECRET_ID_FILE_POLL_SEC`(기본 5초)마다 파일 내용을 확인합니다. 마지막으로 처리한 내용과 다르면 아래 순서로 교체합니다.
1. secret-id를 얻습니다. wrapping 모드라면 unwrap합니다.
2. `tokenManager.switchSecretId()`를 호출합니다. 평소에는 secret-id 조회로 검증하고 보관합니다. 토큰은 유지됩니다(4.6절).
3. 실패하면 ERROR 로그를 남기고 기존 secret-id를 유지합니다. 같은 내용으로 다시 시도하지는 않고, 다음 전달을 기다립니다.

### 6.3 wrapping 모드: unwrap

#### ① 생성 경로 확인 — 토큰을 소모하지 않음

**Vault API**: [`POST /v1/sys/wrapping/lookup`](https://developer.hashicorp.com/vault/api-docs/system/wrapping-lookup) · 토큰 불필요

**Request**
```http
POST /v1/sys/wrapping/lookup
Content-Type: application/json

{ "token": "hvs.CAESIJ0l7Pfdilx0...(wrapping 토큰, 생략)" }
```

**Response** `200`
```json
{
  "data": {
    "creation_path": "auth/approle/role/sample-app/secret-id",
    "creation_time": "2026-10-01T00:51:46.6444248Z",
    "creation_ttl": 300
  }
}
```

앱은 `data.creation_path`가 `auth/{VAULT_APPROLE_MOUNT}/role/{VAULT_ROLE_NAME}/secret-id`와 같은지 확인합니다. 다르면 위조되었거나 잘못 전달된 것으로 보고 거부합니다.

#### ② 풀기 — 1회만 가능

**Vault API**: [`POST /v1/sys/wrapping/unwrap`](https://developer.hashicorp.com/vault/api-docs/system/wrapping-unwrap) · **wrapping 토큰 자체**를 `X-Vault-Token`으로 사용

**Request**
```http
POST /v1/sys/wrapping/unwrap
X-Vault-Token: hvs.CAESIJ0l7Pfdilx0...(wrapping 토큰, 생략)
Content-Type: application/json

{}
```

**Response** `200` — wrapping하기 전의 원래 응답(secret-id 발급 결과)이 그대로 들어 있습니다.
```json
{
  "data": {
    "secret_id": "ffbb9194-d22e-37d1-d55b-42c321578adf",
    "secret_id_accessor": "6df245f8-62e3-5bac-6c10-6d467e77ffba",
    "secret_id_num_uses": 0,
    "secret_id_ttl": 300
  },
  "mount_type": "approle"
}
```

**Java에서 사용하는 필드 → `SecretIdCredential`**

| 응답 필드 | `SecretIdCredential` 필드 |
| --- | --- |
| `data.secret_id` | `secretId` |
| (받은 시각) | `loadedAt` |

unwrap 결과는 `verified=false` 상태로 만듭니다. 만료 시각 등은 `TokenManager`가 secret-id 조회로 다시 확인합니다(4.9절). 원문 모드와 같은 경로로 처리하기 위해서입니다.

**오류**

| 상황 | 응답 | 앱 로그 |
| --- | --- | --- |
| 이미 unwrap된 토큰 (전달 중 누군가 먼저 열어 봄) 또는 만료 | `400 {"errors":["wrapping token is not valid or does not exist"]}` | `wrapping 토큰이 유효하지 않습니다(만료되었거나 이미 사용됨 ...)` |
| 다른 API로 만든 wrapping 토큰 | lookup은 성공하지만 `creation_path`가 다름 | `wrapping 토큰 생성 경로가 다릅니다` |

---

## 7. KV v2 조회/저장 — `KvSecretClient`

KV v2 엔진의 실제 API 경로에는 `data/`가 들어갑니다. 예를 들어 CLI의 `vault kv get secret/sample-app/config`는 `GET /v1/secret/data/sample-app/config`를 호출합니다.

### 7.1 `KvSecret read(String path)`

**Vault API**: [`GET /v1/{mount}/data/{path}`](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2#read-secret-version) · 정책에 `read` 권한 필요

**Java**
```java
KvSecret secret = kv.read("sample-app/config");
secret.data();     // {db_url=..., password=..., username=...}
secret.version();  // 7
```

**Request**
```http
GET /v1/secret/data/sample-app/config
X-Vault-Token: hvs.CAESIL99EM7OSEd9...(생략)
```

**Response** `200`
```json
{
  "data": {
    "data": {
      "db_url": "jdbc:postgresql://db.example.local:5432/app",
      "password": "S3cr3t-P@ss",
      "username": "app_user"
    },
    "metadata": {
      "created_time": "2026-10-01T00:39:35.990327044Z",
      "custom_metadata": null,
      "deletion_time": "",
      "destroyed": false,
      "version": 7
    }
  },
  "mount_type": "kv"
}
```

**Java에서 사용하는 필드 → `KvSecret`**

| 응답 필드 | `KvSecret` 필드 |
| --- | --- |
| `data.data` | `data` (`Map<String, Object>`, 숫자/객체 값도 그대로) |
| `data.metadata.version` | `version` |

**오류**

| 상황 | 응답 |
| --- | --- |
| 시크릿 없음 | `404 {"errors":[]}` |
| 정책에 없는 경로 | `403 {"errors":["1 error occurred:\n\t* permission denied\n\n"]}` |

### 7.2 `int write(String path, Map<String, Object> data)`

경로가 없으면 새로 만들고, 있으면 새 버전으로 덮어씁니다. KV v2는 이전 버전을 보관합니다.

**Vault API**: [`POST /v1/{mount}/data/{path}`](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2#create-update-secret) · 정책에 `create`(새 경로)와 `update`(기존 경로) 권한 필요

**Java**
```java
int version = kv.write("sample-app/doc-sample", Map.of("api_key", "abc123", "retry", 3));
```

**Request** — 저장할 값을 `data` 키로 한 번 감싸서 보냅니다.
```http
POST /v1/secret/data/sample-app/doc-sample
X-Vault-Token: hvs.CAESIL99EM7OSEd9...(생략)
Content-Type: application/json

{
  "data": {
    "api_key": "abc123",
    "retry": 3
  }
}
```

**Response** `200`
```json
{
  "data": {
    "created_time": "2026-10-01T00:51:57.456366305Z",
    "custom_metadata": null,
    "deletion_time": "",
    "destroyed": false,
    "version": 1
  },
  "mount_type": "kv"
}
```

반환값: `data.version`

**오류**

| 상황 | 응답 |
| --- | --- |
| 정책에 없는 경로 | `403 {"errors":["1 error occurred:\n\t* permission denied\n\n"]}` |

---

## 8. 앱이 제공하는 API — `KvApiServer`

외부 클라이언트가 호출하는 앱의 API와, 그 요청이 내부에서 호출하는 Vault API를 정리합니다.

| 앱 API | 내부 Java 호출 | Vault API |
| --- | --- | --- |
| `GET /v1/kv/{path}` | `KvSecretClient.read(path)` | `GET /v1/secret/data/{path}` |
| `PUT` / `POST /v1/kv/{path}` | `KvSecretClient.write(path, body)` | `POST /v1/secret/data/{path}` |
| `GET /health` | `TokenManager.currentToken()`, `currentCredential()` | 없음 |

### 8.1 `GET /v1/kv/{path}`

**Request**
```http
GET /v1/kv/sample-app/config HTTP/1.1
Host: 127.0.0.1:8080
```

**Response** `200` — Vault 응답의 `data.data`와 `metadata.version`만 추려서 돌려줍니다.
```json
{"path":"sample-app/config","version":3,"data":{"db_url":"jdbc:postgresql://db.example.local:5432/app","password":"S3cr3t-P@ss","username":"app_user"}}
```

### 8.2 `PUT /v1/kv/{path}`

**Request** — 바디는 저장할 key/value 객체 그대로입니다. `{"data": ...}`로 감싸는 일은 앱이 대신 합니다.
```http
PUT /v1/kv/sample-app/api-test HTTP/1.1
Host: 127.0.0.1:8080
Content-Type: application/json

{"api_key":"abc123","retry":3,"nested":{"a":1}}
```

**Response** `200`
```json
{"version":1,"path":"sample-app/api-test"}
```

### 8.3 `GET /health`

| 필드 | 의미 |
| --- | --- |
| `status` | 토큰이 만료 전이면 `UP`(200), 없거나 만료되었으면 `DOWN`(503). 앱이 계산한 만료 시각 기준이며, Vault에서 강제 폐기된 것은 다음 Vault 호출에서 감지됩니다. |
| `loginBlocked` | 재로그인할 수 없는 사유 (4.8절). `UP`인데 값이 있으면, 토큰을 잃었을 때 복구할 수 없다는 뜻입니다. **모니터링 대상입니다.** |
| `secretId.verified` | secret-id 조회로 확인했는지 |
| `secretId.expiresAt` / `remainingSec` | 다음 로그인에 쓸 secret-id의 만료 시각 / 남은 초 (`-1` = 모름 또는 만료 없음) |

**Response** `200` (실제 응답)
```json
{"status":"UP","token":{"expiresAt":"2026-10-01T01:29:24.879650Z","remainingSec":59,"accessor":"VTLi7XbWMyHJpPSoFaK0WVx0"},"secretId":{"accessor":"aee423a8-b631-afa0-1fab-9de365c51c41","verified":true,"expiresAt":"2026-10-01T01:33:21.936776635Z","remainingSec":296,"loadedAt":"2026-10-01T01:28:24.822919Z"}}
```

**Response** `200` — 토큰은 살아 있지만 secret-id가 만료된 경우 (지금은 정상, 토큰을 잃으면 복구 불가)
```json
{"status":"UP","loginBlocked":"SECRET_ID_EXPIRED","token":{...},"secretId":{"verified":true,"remainingSec":0,...}}
```

### 8.4 오류 응답

| 상태 | 원인 | 응답 예 |
| --- | --- | --- |
| 400 | `..` 등 잘못된 경로 | `{"error":"잘못된 경로: sample-app/../../sys/policy"}` |
| 400 | JSON 객체가 아닌 바디 | `{"error":"요청 바디는 JSON 객체여야 합니다."}` |
| 403 | Vault 정책 거부 | `{"error":"Vault 오류 403 : secret/data/other-app/x [\"1 error occurred:\\n\\t* permission denied\\n\\n\"]"}` |
| 404 | 시크릿 없음 | `{"error":"Vault 오류 404 : secret/data/sample-app/nope []"}` |
| 503 | 지금은 Vault 로그인 불가 (secret-id 거부 / 잠금 대기) | `{"error":"현재 secret-id 가 Vault 에서 거부되어 새 secret-id 전달을 기다리는 중입니다."}` |
| 405 | 지원하지 않는 메서드 | `{"error":"지원하지 않는 메서드: DELETE"}` |
| 502 | Vault 연결 실패 / Vault 5xx | `{"error":"Vault 호출 실패(네트워크): ..."}` |

---

## 9. 오케스트레이터가 호출하는 API (`scripts/deliver-secret-id.sh`)

Java 앱이 아니라 오케스트레이터가 호출하는 API입니다. 앱에는 이 API를 호출할 권한이 없습니다.

**Vault API**: [`POST /v1/auth/approle/role/{role_name}/secret-id`](https://developer.hashicorp.com/vault/api-docs/auth/approle#generate-new-secret-id) · `sample-app-orchestrator` 정책의 `update` 권한 필요

### 9.1 원문 전달 (`ORCH_WRAPPED=false`)

**Request**
```http
POST /v1/auth/approle/role/sample-app/secret-id
X-Vault-Token: (오케스트레이터 토큰)
Content-Type: application/json

{}
```

**Response** `200`
```json
{
  "data": {
    "secret_id": "05586c39-2014-1a77-c6fa-ea7b23a77497",
    "secret_id_accessor": "ca176269-b621-5bfe-0a38-ecbfda38e48e",
    "secret_id_num_uses": 0,
    "secret_id_ttl": 300
  },
  "mount_type": "approle"
}
```
오케스트레이터는 `data.secret_id`를 파일에 씁니다.

### 9.2 wrapping 전달 (`ORCH_WRAPPED=true`)

`X-Vault-Wrap-TTL` 헤더를 붙이면, Vault가 응답을 바로 돌려주지 않고 1회용 wrapping 토큰에 담아 줍니다. CLI의 `-wrap-ttl=5m` 옵션이 이 헤더를 붙입니다.

**Request**
```http
POST /v1/auth/approle/role/sample-app/secret-id
X-Vault-Token: (오케스트레이터 토큰)
X-Vault-Wrap-TTL: 5m
Content-Type: application/json

{}
```

**Response** `200` — `data`는 비어 있고, `wrap_info`에 wrapping 토큰이 들어 있습니다.
```json
{
  "data": null,
  "wrap_info": {
    "token": "hvs.CAESIJ0l7Pfdilx0...(생략)",
    "accessor": "uwrJFfkoytMFbrUBOuImPhY7",
    "ttl": 300,
    "creation_time": "2026-10-01T00:51:46.6444248Z",
    "creation_path": "auth/approle/role/sample-app/secret-id",
    "wrapped_accessor": "6df245f8-62e3-5bac-6c10-6d467e77ffba"
  }
}
```
오케스트레이터는 `wrap_info.token`을 파일에 씁니다. 따라서 오케스트레이터는 secret-id 원문을 보지 않습니다.

---

## 10. 데이터 클래스

| 클래스 | 필드 | 주요 메서드 |
| --- | --- | --- |
| `VaultToken` (record, 불변) | `token`, `accessor`, `leaseDurationSec`, `renewable`, `issuedAt` | `expiresAt()`, `remainingSec()`, `isExpiringWithin(Duration)`, `withRenewedLease(long)`, `sameTokenAs(VaultToken)` |
| `SecretIdCredential` (record, 불변) | `secretId`, `accessor`, `loadedAt`, `verified`, `expiresAt` | `unverified(String)` — 전달받은 secret-id로 생성, `withLookup(accessor, expiresAt)` — 조회 결과 반영, `isExpired()`, `remainingSec()` |
| `KvSecret` (record, 불변) | `data`, `version` | - |
| `VaultException` | `statusCode` | `isPermissionDenied()` |
| `LoginUnavailableException` (`VaultException` 하위) | `statusCode=503` | - |

`VaultToken`과 `SecretIdCredential`의 `toString()`은 토큰과 secret-id 원문을 빼고 accessor만 출력합니다. 로그에 민감 정보가 남지 않게 하기 위해서입니다.

---

## 11. 한눈에 보기

| Java 메서드 | Vault API | 사용하는 토큰 | 필요한 정책 |
| --- | --- | --- | --- |
| `AppRoleAuthenticator.login` (앱 시작, 장애 복구 때만) | `POST auth/approle/login` | 없음 | 없음 (role-id + secret-id) |
| `TokenManager.executeWithToken` (403 시) | `GET auth/token/lookup-self` | 앱 토큰 | `default` |
| `TokenManager.revokeCurrent` / 이전 토큰 폐기 | `POST auth/token/revoke-self` | 폐기할 토큰 | `default` |
| `TokenRenewer.start` | `GET auth/token/lookup-self` (period 확인) | 앱 토큰 | `default` |
| `TokenRenewer.renew` | `POST auth/token/renew-self` | 앱 토큰 | `default` |
| `SecretIdLookup.lookup` (`switchSecretId`, 로그인 직후) | `POST auth/approle/role/sample-app/secret-id/lookup` | 앱 토큰 | `sample-app`: update |
| `SecretIdFileWatcher` (wrapped) | `POST sys/wrapping/lookup` | 없음 | 없음 |
| `SecretIdFileWatcher` (wrapped) | `POST sys/wrapping/unwrap` | wrapping 토큰 | 없음 |
| `KvSecretClient.read` | `GET secret/data/{path}` | 앱 토큰 | `sample-app`: read |
| `KvSecretClient.write` | `POST secret/data/{path}` | 앱 토큰 | `sample-app`: create, update |
| (오케스트레이터 스크립트) | `POST auth/approle/role/sample-app/secret-id` | 오케스트레이터 토큰 | `sample-app-orchestrator`: update |
