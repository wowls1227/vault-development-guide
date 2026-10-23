# KV v2 시크릿 관리 API 가이드 (저장 · 조회 · 업데이트 · 변경 · 삭제 + 메타데이터)

KV v2 엔진에서 시크릿을 저장하고, 조회하고, 업데이트·변경하고, 삭제하는 API를 정리합니다. 저장·조회·변경할 때 **사용자 메타데이터(`custom_metadata`)를 함께 다루는 방법**과, KV를 호출하기 전 로그인 방식인 **AppRole / Certificate 인증**도 같이 다룹니다.

- 기준: Vault OSS **2.0.3** dev 서버(`-dev-tls`)에서 2026-10-23에 아래 순서대로 직접 호출한 결과입니다. 버전 번호와 응답은 그 흐름을 그대로 따릅니다.
- KV 마운트는 `secret`, 예시 경로는 `sample-app/doc-sample`입니다.
- 요청 예시에는 네임스페이스 `team-a`를 쓰는 경우의 `X-Vault-Namespace` 헤더를 함께 적었습니다. root 네임스페이스나 OSS에서는 이 헤더를 빼면 됩니다 (1.4절).
- 전체 API 명세는 [VAULT_API_SPEC.md](VAULT_API_SPEC.md), Java 함수별 설명은 [VAULT_API_GUIDE.md](VAULT_API_GUIDE.md)를 참고하세요.
- **현재 앱 구현 범위**: 앱은 AppRole 로그인, KV 조회(최신), KV 저장만 구현되어 있습니다. 나머지는 Vault API 기준으로 정리했고, 앱에 넣을 때의 변경점은 10장에 적었습니다.

---

## 1. 개요

### 1.1 KV v2 경로 구조

KV v2는 하나의 시크릿을 용도별 경로로 나누어 다룹니다. CLI의 `vault kv ...` 명령도 내부적으로 아래 경로를 호출합니다.

| 경로 | 대상 | 비고 |
| --- | --- | --- |
| `secret/data/{path}` | 시크릿 값 (버전별) | 저장, 조회, 부분 변경, 최신 버전 삭제 |
| `secret/metadata/{path}` | 메타데이터 (버전 목록, 설정, `custom_metadata`) | 버전과 무관하게 시크릿당 하나. 목록 조회, 전체 영구 삭제 |
| `secret/delete/{path}` | 지정 버전 삭제 (복구 가능) | |
| `secret/undelete/{path}` | 삭제한 버전 복구 | |
| `secret/destroy/{path}` | 지정 버전 영구 삭제 (복구 불가) | |
| `secret/subkeys/{path}` | 값 없이 키 구조만 조회 | |

### 1.2 용어

| 용어 | 의미 |
| --- | --- |
| 버전 | 저장·업데이트·변경할 때마다 1씩 늘어나는 번호. 이전 버전은 `max_versions`까지 보관 |
| 삭제 (soft delete) | 값을 숨기지만 복구(undelete)할 수 있음. `deletion_time`이 기록됨 |
| 영구 삭제 (destroy) | 해당 버전의 값을 지움. 복구 불가. `destroyed=true` |
| 전체 삭제 | 메타데이터와 모든 버전을 지움. 복구 불가 |
| `custom_metadata` | 사용자가 붙이는 문자열 key/value (담당자, 설명, 교체 주기 등). **버전 관리되지 않음** |
| CAS (check-and-set) | "현재 버전이 N일 때만 저장"하는 낙관적 잠금. 동시 수정 덮어쓰기 방지 |

### 1.3 작업별 API 요약

| 작업 | Method | Path | 필요 권한 (capability) | 앱 구현 |
| --- | --- | --- | --- | --- |
| [저장 (생성)](#3-저장-생성--메타데이터) | POST | `secret/data/{path}` | `create` | O (`PUT /v1/kv/{path}`) |
| [메타데이터 저장](#32-메타데이터-저장) | POST | `secret/metadata/{path}` | `create`, `update` | X |
| [조회 (최신 / 버전)](#41-시크릿-조회) | GET | `secret/data/{path}[?version=N]` | `read` | 최신만 O |
| [메타데이터 조회](#42-메타데이터-조회) | GET | `secret/metadata/{path}` | `read` | X |
| [키 구조 조회](#43-키-구조-조회-subkeys) | GET | `secret/subkeys/{path}` | `read` | X |
| [목록](#44-목록-조회) | LIST | `secret/metadata/{path}/` | `list` | X |
| [업데이트 (전체 덮어쓰기)](#5-업데이트-전체-덮어쓰기) | POST | `secret/data/{path}` | `update` | O (CAS 없이) |
| [변경 (부분 수정)](#61-값-부분-변경) | PATCH | `secret/data/{path}` | `patch` | X |
| [메타데이터 변경](#62-메타데이터-변경) | PATCH | `secret/metadata/{path}` | `patch` | X |
| [최신 버전 삭제](#71-최신-버전-삭제) | DELETE | `secret/data/{path}` | `delete` | X |
| [지정 버전 삭제](#72-지정-버전-삭제) | POST | `secret/delete/{path}` | `update` | X |
| [복구](#73-복구-undelete) | POST | `secret/undelete/{path}` | `update` | X |
| [영구 삭제](#74-영구-삭제-destroy) | POST | `secret/destroy/{path}` | `update` | X |
| [전체 삭제](#75-전체-삭제) | DELETE | `secret/metadata/{path}` | `delete` | X |

### 1.4 공통 요청 헤더 (네임스페이스 포함)

| 헤더 | 값 | 대상 |
| --- | --- | --- |
| `X-Vault-Namespace` | `team-a` (중첩: `parent/child`) | Enterprise 네임스페이스를 쓸 때 **모든 요청** — 로그인, 토큰 갱신, KV 전체 |
| `X-Vault-Token` | 로그인으로 받은 토큰 | 로그인을 뺀 모든 요청 |
| `Content-Type` | `application/json` / PATCH는 `application/merge-patch+json` | 바디가 있는 요청 |

네임스페이스를 쓸 때 KV 작업에서 지킬 것:

| 항목 | 내용 |
| --- | --- |
| 로그인과 같은 네임스페이스 | 토큰은 로그인한 네임스페이스(와 하위)에서만 유효합니다. 로그인할 때 보낸 `X-Vault-Namespace`를 KV 요청과 토큰 갱신에도 똑같이 보내세요. 다른 네임스페이스로 보내면 `403` |
| 경로는 네임스페이스 기준 | `team-a`에서 `secret/data/sample-app/doc-sample`은 `team-a` 안의 `secret` 마운트입니다. 마운트, 인증 방식(AppRole·Certificate), 정책을 그 네임스페이스 안에 만들어야 합니다 |
| 관리자 CLI | `export VAULT_NAMESPACE=team-a` 후 2장의 구성 명령 실행 (또는 명령마다 `-namespace=team-a`) |
| 앱 | `VAULT_NAMESPACE=team-a`를 지정하면 `VaultHttpClient`가 모든 Vault 요청에 헤더를 붙입니다 |
| OSS | 헤더를 무시하고 root에서 처리합니다 (오류 없음, 2.0.3에서 확인). 네임스페이스 값이 틀려도 OSS에서는 드러나지 않으니 Enterprise 환경에서 따로 확인하세요 |

```bash
curl -H "X-Vault-Namespace: team-a" -H "X-Vault-Token: $TOKEN" \
     https://vault.example:8200/v1/secret/data/sample-app/doc-sample
```

---

## 2. 인증 — 토큰 받기

KV API는 모두 `X-Vault-Token` 헤더의 토큰으로 호출합니다. 네임스페이스를 쓰면 로그인부터 `X-Vault-Namespace` 헤더를 붙입니다. 토큰을 받는 방법(approle or 인증서)만 다르고, **받은 뒤 KV 호출 방법은 두 방식이 같습니다.**

```
(모든 요청: X-Vault-Namespace: <ns>)
[AppRole]      role-id + secret-id  ── POST auth/approle/login ──┐
                                                                 ├─▶ 토큰 ─▶ KV API (X-Vault-Token)
[Certificate]  클라이언트 인증서(mTLS) ── POST auth/cert/login ────┘          토큰 갱신: POST auth/token/renew-self
```

### 2.1 AppRole

**사전 구성 (관리자)** — `scripts/setup-vault.sh`가 수행합니다.

```bash
export VAULT_NAMESPACE=team-a   # Enterprise 네임스페이스를 쓰는 경우 (root면 생략)
vault auth enable approle
vault policy write sample-app-kv sample-app-kv.hcl          # 2.4절 정책
vault write auth/approle/role/sample-app token_policies=sample-app,sample-app-kv token_period=1m secret_id_ttl=5m
vault read -field=role_id auth/approle/role/sample-app/role-id          # → 앱 배포 시 주입
vault write -f -field=secret_id auth/approle/role/sample-app/secret-id   # → 오케스트레이터가 전달
```

**로그인**

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
    "renewable": true
  }
}
```

| 오류 | 응답 |
| --- | --- |
| secret-id 틀림 / 만료 / 폐기 | `400 {"errors":["invalid role or secret ID"]}` |
| 연속 실패로 role-id 잠김 | `403 {"errors":["permission denied"]}` |

**토큰 갱신**: `POST /v1/auth/token/renew-self` (`X-Vault-Token`과, 네임스페이스를 쓰면 로그인 때와 같은 `X-Vault-Namespace`). 앱은 `TokenRenewer`가 20초마다 호출합니다.

### 2.2 Certificate (TLS 클라이언트 인증서)

클라이언트 인증서로 Vault와 mTLS 연결을 맺어 로그인합니다. 비밀번호 같은 문자열 대신 **개인키를 가진 쪽만 로그인**할 수 있습니다.

**전제 조건**

| 항목 | 내용 |
| --- | --- |
| Vault 리스너 | **TLS 필수**. 클라이언트 인증서는 TLS 핸드셰이크로 전달되므로 `tls_disable=true` 리스너에서는 로그인 불가. 앞단에 LB/프록시가 있으면 TLS passthrough |
| 클라이언트 인증서 | Vault에 등록한 CA가 서명, EKU `clientAuth`, 역할의 제약(`allowed_common_names` 등) 만족 |
| 개인키 | 앱 서버에만 보관 (권한 600). Java는 PKCS#12로 묶어 쓰면 편함 |

**사전 구성 (관리자)**

```bash
export VAULT_NAMESPACE=team-a   # Enterprise 네임스페이스를 쓰는 경우 (root면 생략)
vault auth enable cert
vault write auth/cert/certs/sample-app \
  certificate=@client-ca.pem \
  allowed_common_names=sample-app.poc.local \
  display_name=sample-app \
  token_policies=sample-app-kv \
  token_period=1m
```

| 파라미터 | 설명 |
| --- | --- |
| `certificate` | 신뢰할 CA (PEM). 이 CA가 서명한 인증서만 로그인 |
| `allowed_common_names` | 허용 CN. 글롭(`*.poc.local`) 가능. `allowed_dns_sans`, `allowed_organizational_units`, `required_extensions` 등으로 더 좁힐 수 있음 |
| `token_policies` / `token_period` | 토큰 정책 / periodic 토큰 |
| `ocsp_enabled` | OCSP로 폐기 여부 확인 (선택). CRL은 `auth/cert/crls/{name}`에 등록 |

**로그인**

```bash
curl --cacert vault-ca.pem -H "X-Vault-Namespace: team-a" \
     --cert sample-app.poc.local.crt --key sample-app.poc.local.key \
     -X POST https://vault.example:8200/v1/auth/cert/login \
     -d '{"name":"sample-app"}'
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
    "renewable": true
  }
}
```

- `name`을 생략해도 Vault가 등록된 역할 중 인증서에 맞는 것을 찾아 로그인합니다. 역할이 여러 개면 명시하는 것이 좋습니다.
- 토큰 `display_name`은 `cert-sample-app`이 되고, 감사 로그와 KV 메타데이터의 `created_by.actor`에도 `cert`로 남습니다.

| 오류 | 응답 |
| --- | --- |
| 인증서를 제시하지 않음 | `400 {"errors":["client certificate must be supplied"]}` |
| CN 불일치, 신뢰하지 않는 CA | `400 {"errors":["failed to match all constraints for this login certificate"]}` |

**토큰 갱신 — 인증서를 다시 제시해야 함**

Certificate 방식으로 받은 토큰은 `renew-self`를 호출할 때도 **로그인 때와 같은 클라이언트 인증서**를 TLS로 제시해야 합니다. Vault가 갱신 시점에 인증서가 여전히 유효한지 다시 확인하기 때문입니다.

| 호출 | 결과 |
| --- | --- |
| 토큰만으로 `POST auth/token/renew-self` | `400 {"errors":["client certificate must be supplied"]}` |
| 토큰 + 같은 클라이언트 인증서 | `200` (TTL 다시 채움) |

따라서 앱에 Certificate 방식을 넣을 때는 **로그인뿐 아니라 갱신 요청도 같은 `SSLContext`를 쓰는 `HttpClient`로 보내야 합니다.** 인증서가 만료되면 갱신도 실패하므로 클라이언트 인증서 교체 절차(만료 전 재발급 → 파일 교체 → `SSLContext` 재생성)가 필요합니다.

**Java 구현 포인트**

```java
// 1) 클라이언트 인증서 + 개인키 (openssl pkcs12 -export -in client.crt -inkey client.key -out client.p12)
KeyStore keyStore = KeyStore.getInstance("PKCS12");
try (InputStream in = Files.newInputStream(Path.of("client.p12"))) {
    keyStore.load(in, p12Password);
}
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(keyStore, p12Password);

// 2) Vault 서버 인증서를 검증할 CA
KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
trustStore.load(null, null);
try (InputStream in = Files.newInputStream(Path.of("vault-ca.pem"))) {
    trustStore.setCertificateEntry("vault-ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
}
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);

// 3) 모든 Vault 호출(로그인, 갱신, KV)에 같은 HttpClient 사용
SSLContext ssl = SSLContext.getInstance("TLS");
ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();

// 4) 로그인: 바디에 역할 이름만, 토큰 헤더 없음. 네임스페이스를 쓰면 헤더 추가
HttpRequest login = HttpRequest.newBuilder(URI.create(vaultAddr + "/v1/auth/cert/login"))
        .header("Content-Type", "application/json")
        .header("X-Vault-Namespace", namespace)          // 네임스페이스를 쓰는 경우만
        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"sample-app\"}"))
        .build();
// → auth.client_token. 이후 KV·갱신 요청에도 같은 X-Vault-Namespace 를 붙임
```

### 2.3 두 방식 비교

| 항목 | AppRole | Certificate |
| --- | --- | --- |
| 로그인 자격증명 | role-id + secret-id (문자열) | 클라이언트 인증서 + 개인키 |
| 최초 배포 | secret-id 전달 (오케스트레이터, Response Wrapping) | 인증서·키 배포 (서버에서 CSR을 만들면 개인키 이동 없음) |
| Vault 리스너 | TLS 권장 | **TLS 필수** |
| 토큰 갱신 | 토큰만 있으면 됨 | **같은 인증서를 다시 제시** |
| 자격증명 교체 | secret-id 재발급 | 인증서 만료 전 재발급 |
| 폐기 | secret-id accessor로 즉시 폐기 | CRL 등록 / OCSP / 신뢰 CA 교체 |
| 감사 로그 식별 | `display_name=approle`, `role_name` | `display_name=cert-<이름>`, CN·일련번호 |
| KV 사용법 | 동일 | 동일 |

자세한 장단점은 PoC 엑셀의 "인증 방식 비교" 시트를 참고하세요.

### 2.4 KV 전체 작업용 정책

작업별로 필요한 capability가 다릅니다. 아래는 저장·조회·업데이트·변경·삭제와 메타데이터를 모두 허용하는 정책입니다. 실제로는 필요한 작업만 남기세요.

```hcl
# 값: 저장(create) / 조회(read) / 업데이트(update) / 부분 변경(patch) / 최신 버전 삭제(delete)
path "secret/data/sample-app/*" {
  capabilities = ["create", "read", "update", "patch", "delete"]
}
# 메타데이터: 저장 / 조회 / 변경 / 전체 삭제 / 목록
path "secret/metadata/sample-app/*" {
  capabilities = ["create", "read", "update", "patch", "delete", "list"]
}
# 지정 버전 삭제 / 복구 / 영구 삭제
path "secret/delete/sample-app/*"   { capabilities = ["update"] }
path "secret/undelete/sample-app/*" { capabilities = ["update"] }
path "secret/destroy/sample-app/*"  { capabilities = ["update"] }
# 값 없이 키 구조만 조회
path "secret/subkeys/sample-app/*"  { capabilities = ["read"] }
```

| 작업 | 경로 | capability |
| --- | --- | --- |
| 새 경로에 저장 | `data/` | `create` |
| 기존 경로에 업데이트 | `data/` | `update` |
| 부분 변경 | `data/` | `patch` (`update`로는 안 됨) |
| 조회 | `data/` | `read` |
| 최신 버전 삭제 | `data/` | `delete` |
| 메타데이터 저장 / 변경 | `metadata/` | `create`·`update` / `patch` |
| 메타데이터 조회 / 목록 | `metadata/` | `read` / `list` |
| 전체 삭제 | `metadata/` | `delete` |
| 지정 버전 삭제·복구·영구 삭제 | `delete/`, `undelete/`, `destroy/` | `update` |

> 메타데이터 조회 권한(`metadata/` `read`)만 있어도 `custom_metadata`가 보입니다. **메타데이터에는 비밀 값을 넣지 마세요.**

---

## 3. 저장 (생성) + 메타데이터

메타데이터는 값과 **다른 경로에 따로 저장**합니다. 그래서 메타데이터가 있는 시크릿을 새로 만들 때는 API를 두 번 호출합니다.

```
① POST secret/data/{path}      값 저장 (version 1)
② POST secret/metadata/{path}  custom_metadata, max_versions 등 저장
```

> 값 저장 요청 바디에 `custom_metadata`를 같이 넣으면 **저장되지 않고 무시**됩니다. 응답 `warnings`에 `Endpoint ignored these unrecognized parameters`가 붙습니다.
>
> 두 호출은 원자적이지 않습니다. ①은 성공하고 ②가 실패하면 메타데이터 없는 시크릿이 남으므로, ②를 재시도하거나 오류로 처리하세요.

### 3.1 값 저장

**Request** — `options.cas=0`이면 **경로가 없을 때만** 저장합니다 (실수로 덮어쓰기 방지).

```http
POST /v1/secret/data/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{"options":{"cas":0},"data":{"api_key":"abc123","retry":3}}
```

| 파라미터 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `data` | object | O | 저장할 key/value. 값은 문자열·숫자·객체 등 JSON 값 |
| `options.cas` | int | X | `0`=없을 때만 저장, `N`=현재 버전이 N일 때만 저장. 생략하면 무조건 저장 (`cas_required=true`면 필수) |

**Response** `200`

```json
{"data":{"created_time":"2026-10-23T06:05:11.088932Z","custom_metadata":null,"deletion_time":"","destroyed":false,"version":1}}
```

| 필드 | 설명 |
| --- | --- |
| `data.version` | 만들어진 버전 |
| `data.created_time` | 버전 생성 시각 |
| `data.custom_metadata` | 현재 메타데이터 (아직 없으면 `null`) |

같은 경로에 `cas=0`으로 다시 저장하면:

```json
400 {"errors":["check-and-set parameter did not match the current version"]}
```

### 3.2 메타데이터 저장

**Request**

```http
POST /v1/secret/metadata/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{
  "max_versions": 10,
  "cas_required": false,
  "delete_version_after": "0s",
  "custom_metadata": {
    "owner": "platform-team",
    "app": "sample-app",
    "description": "외부 API 키",
    "rotation": "90d"
  }
}
```

| 파라미터 | 타입 | 설명 |
| --- | --- | --- |
| `custom_metadata` | map<string,string> | 사용자 메타데이터. **POST는 기존 값을 통째로 교체**합니다. 생략하면 기존 값 유지 |
| `max_versions` | int | 보관할 최대 버전 수 (0 = 엔진 설정값, 기본 10) |
| `cas_required` | bool | `true`면 이 시크릿의 모든 값 저장에 `options.cas` 필수 |
| `delete_version_after` | duration | 버전 생성 후 이 시간이 지나면 자동 삭제(soft). `0s` = 사용 안 함 |

**Response** `204 No Content`

**`custom_metadata` 제약** (실제 확인)

| 항목 | 제약 | 위반 시 |
| --- | --- | --- |
| 키 개수 | 최대 64개 | `400 custom_metadata validation failed: payload must contain at most 64 keys, provided 65` |
| 키 길이 | 1 ~ 128자 | `400 ... length of key "..." is 129 but must be 0 < len(key) <= 128` |
| 값 길이 | 1 ~ 512자 | `400 ... length of value for key "k" is 513 but must be 0 < len(value) <= 512` |
| 값 타입 | 문자열 | 숫자를 보내면 문자열로 저장됨 (`3` → `"3"`) |

**권장 메타데이터 키 예시**

| 키 | 예 | 용도 |
| --- | --- | --- |
| `owner` | `platform-team` | 담당 조직 |
| `app` | `sample-app` | 사용 애플리케이션 |
| `description` | `외부 API 키` | 설명 |
| `rotation` | `90d` | 교체 주기 |
| `updated_by` | `ops` | 마지막 수정 주체 (사람이 읽는 값) |
| `ticket` | `CHG-1234` | 변경 요청 번호 |

> Vault 2.0.3은 메타데이터에 **누가 만들고 지웠는지**(`created_by`, `deleted_by`, `last_updated_by`)를 자동으로 기록합니다(4.2절). 수정 주체를 `custom_metadata`에 따로 넣지 않아도 추적할 수 있습니다.

---

## 4. 조회

### 4.1 시크릿 조회

**Request**

```http
GET /v1/secret/data/sample-app/doc-sample              # 최신 버전
GET /v1/secret/data/sample-app/doc-sample?version=1    # 특정 버전
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```

**Response** `200` — 값과 메타데이터(요약)가 함께 옵니다.

```json
{
  "data": {
    "data": { "api_key": "abc123", "retry": 3 },
    "metadata": {
      "created_time": "2026-10-23T06:05:11.088932Z",
      "custom_metadata": {
        "app": "sample-app",
        "description": "외부 API 키",
        "owner": "platform-team",
        "rotation": "90d"
      },
      "deletion_time": "",
      "destroyed": false,
      "version": 1
    }
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `data.data` | 시크릿 key/value |
| `data.metadata.version` | 조회한 버전 |
| `data.metadata.created_time` | 이 버전의 생성 시각 |
| `data.metadata.custom_metadata` | 사용자 메타데이터 |
| `data.metadata.deletion_time` | 삭제(soft)된 시각. 삭제되지 않았으면 `""` |
| `data.metadata.destroyed` | 영구 삭제 여부 |

> `custom_metadata`는 **버전과 무관하게 현재 값**입니다. 메타데이터를 바꾼 뒤 `?version=1`을 조회해도 바뀐 메타데이터가 나옵니다. 버전별 이력이 필요한 정보는 값(`data`)에 넣으세요.

| 오류 | 응답 |
| --- | --- |
| 경로 없음 / 전체 삭제됨 | `404 {"errors":[]}` |
| 삭제(soft) 또는 영구 삭제된 버전 | `404` + `data.data=null`, `metadata.deletion_time` 또는 `destroyed=true` (7장) |
| 정책 밖 경로 | `403 {"errors":["1 error occurred:\n\t* permission denied\n\n"]}` |

### 4.2 메타데이터 조회

**Request**

```http
GET /v1/secret/metadata/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```

**Response** `200` (4번 버전까지 만든 뒤)

```json
{
  "data": {
    "cas_required": false,
    "created_time": "2026-10-23T06:05:11.088932Z",
    "current_version": 4,
    "custom_metadata": { "app": "sample-app", "owner": "platform-team", "rotation": "30d", "updated_by": "ops" },
    "delete_version_after": "0s",
    "last_updated_by": { "actor": "approle", "client_id": "577dfcb5-...", "entity_id": "577dfcb5-...", "operation": "patch" },
    "max_versions": 10,
    "oldest_version": 0,
    "updated_time": "2026-10-23T06:05:11.196018Z",
    "versions": {
      "1": {
        "created_by": { "actor": "approle", "client_id": "577dfcb5-...", "entity_id": "577dfcb5-...", "operation": "create" },
        "created_time": "2026-10-23T06:05:11.088932Z",
        "deleted_by": null,
        "deletion_time": "",
        "destroyed": false
      },
      "2": { "created_by": { "operation": "update", "...": "..." }, "...": "..." },
      "3": { "created_by": { "operation": "patch", "...": "..." }, "...": "..." },
      "4": { "created_by": { "operation": "patch", "...": "..." }, "...": "..." }
    }
  }
}
```

| 필드 | 설명 |
| --- | --- |
| `current_version` / `oldest_version` | 최신 / 가장 오래된 보관 버전 |
| `custom_metadata` | 사용자 메타데이터 |
| `max_versions`, `cas_required`, `delete_version_after` | 3.2절 설정 |
| `created_time` / `updated_time` | 시크릿 최초 생성 / 마지막 버전 생성 시각 |
| `versions.{N}` | 버전별 `created_time`, `deletion_time`, `destroyed`, `created_by`, `deleted_by` |
| `created_by` / `deleted_by` / `last_updated_by` | 작업 주체. `actor`(로그인 방식: `approle`, `cert` 등), `entity_id`(로그인 응답의 `entity_id`와 같음), `operation`(`create`, `update`, `patch`, `delete`, `undelete` 등). Vault 2.0.3 응답에서 확인했으며, 이전 버전에는 없을 수 있음 |

### 4.3 키 구조 조회 (subkeys)

값을 노출하지 않고 어떤 키가 있는지만 봅니다. 값은 모두 `null`로 옵니다.

```http
GET /v1/secret/subkeys/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```
```json
{"data":{"subkeys":{"api_key":null,"retry":null},"metadata":{"version":4,"custom_metadata":{"app":"sample-app","owner":"platform-team","rotation":"30d","updated_by":"ops"},"...":"..."}}}
```

선택 파라미터: `version`, `depth` (중첩 객체를 몇 단계까지 펼칠지)

### 4.4 목록 조회

경로 끝에 `/`를 붙입니다. `LIST` 메서드 대신 `GET ...?list=true`도 됩니다.

```http
LIST /v1/secret/metadata/sample-app/
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```
```json
{"data":{"keys":["config","doc-sample"]}}
```

하위 폴더는 `"folder/"`처럼 `/`로 끝나는 이름으로 옵니다. 값은 반환하지 않습니다.

---

## 5. 업데이트 (전체 덮어쓰기)

값 저장(3.1절)과 같은 API입니다. 기존 경로에 저장하면 **새 버전이 만들어지고, 보낸 `data`가 그 버전의 값 전체**가 됩니다. 보내지 않은 키는 새 버전에서 사라집니다(이전 버전에는 남음). 메타데이터는 그대로 유지됩니다.

**Request** — `cas`로 "현재 버전이 1일 때만" 저장합니다.

```http
POST /v1/secret/data/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{"options":{"cas":1},"data":{"api_key":"def456","retry":5}}
```

**Response** `200` — 메타데이터가 유지된 것을 응답에서 볼 수 있습니다.

```json
{"data":{"created_time":"2026-10-23T06:05:11.142552Z","custom_metadata":{"app":"sample-app","description":"외부 API 키","owner":"platform-team","rotation":"90d"},"deletion_time":"","destroyed":false,"version":2}}
```

그 사이 다른 쪽이 먼저 저장해서 버전이 바뀌었으면:

```json
400 {"errors":["check-and-set parameter did not match the current version"]}
```

> **언제 업데이트를 쓰나**: 값 전체를 새로 정할 때 (예: 비밀번호 교체 시 관련 키를 한꺼번에). 일부 키만 바꿀 때는 6.1절 변경(PATCH)을 쓰면 다른 키를 다시 보낼 필요가 없습니다.

---

## 6. 변경 (부분 수정)

### 6.1 값 부분 변경

`PATCH`는 보낸 키만 바꾸고 나머지는 유지한 **새 버전**을 만듭니다 (JSON Merge Patch, RFC 7386).

| 규칙 | 내용 |
| --- | --- |
| Content-Type | **`application/merge-patch+json` 필수**. `application/json`이면 `415 PATCH requires Content-Type of application/merge-patch+json` |
| 보낸 키 | 값을 바꾸거나 새로 추가 |
| 값이 `null`인 키 | 그 키를 삭제 |
| 보내지 않은 키 | 유지 |
| 중첩 객체 | 재귀적으로 병합 |
| 경로가 없을 때 | `404` (PATCH로는 새로 만들 수 없음 → 3.1절 저장 사용) |
| `options.cas` | 지원 |
| 권한 | `data/` 경로의 `patch` capability |

**Request** — `retry`를 바꾸고 `timeout_ms`를 추가

```http
PATCH /v1/secret/data/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/merge-patch+json

{"data":{"retry":7,"timeout_ms":3000}}
```

**Response** `200` → version 3. 값은 `{"api_key":"def456","retry":7,"timeout_ms":3000}`

**Request** — `timeout_ms` 키 삭제

```http
PATCH /v1/secret/data/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/merge-patch+json

{"data":{"timeout_ms":null}}
```

**Response** `200` → version 4. 값은 `{"api_key":"def456","retry":7}`

```json
{"data":{"created_time":"2026-10-23T06:05:11.196018Z","custom_metadata":{"app":"sample-app","description":"외부 API 키","owner":"platform-team","rotation":"90d"},"deletion_time":"","destroyed":false,"version":4}}
```

### 6.2 메타데이터 변경

메타데이터는 두 가지 방법으로 바꿀 수 있습니다.

| 방법 | `custom_metadata` 처리 | 용도 |
| --- | --- | --- |
| `PATCH secret/metadata/{path}` | 보낸 키만 병합, `null`이면 그 키 삭제 | 일부 키만 변경 (권장) |
| `POST secret/metadata/{path}` | **보낸 값으로 통째로 교체** (생략하면 유지) | 전체를 새로 정할 때 |

**Request** — `rotation` 변경, `description` 삭제, `updated_by` 추가

```http
PATCH /v1/secret/metadata/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/merge-patch+json

{"custom_metadata":{"rotation":"30d","description":null,"updated_by":"ops"}}
```

**Response** `204 No Content`. 이후 조회하면:

```json
"custom_metadata": { "app": "sample-app", "owner": "platform-team", "rotation": "30d", "updated_by": "ops" }
```

> 메타데이터 변경은 **새 버전을 만들지 않습니다.** 값의 버전은 그대로 4입니다.

`max_versions`, `cas_required`, `delete_version_after`도 같은 PATCH로 바꿀 수 있습니다.

### 6.3 값과 메타데이터를 함께 변경

값 변경(6.1)과 메타데이터 변경(6.2)은 별도 호출입니다. 함께 바꿔야 하면 값 → 메타데이터 순서로 호출하고, 두 번째가 실패하면 재시도하세요.

---

## 7. 삭제

| 방법 | API | 대상 | 복구 | 응답 |
| --- | --- | --- | --- | --- |
| 최신 버전 삭제 | `DELETE secret/data/{path}` | 최신 버전 | 가능 (7.3) | `204` |
| 지정 버전 삭제 | `POST secret/delete/{path}` | 지정 버전들 | 가능 (7.3) | `204` |
| 영구 삭제 | `POST secret/destroy/{path}` | 지정 버전들의 값 | **불가** | `204` |
| 전체 삭제 | `DELETE secret/metadata/{path}` | 메타데이터 + 모든 버전 | **불가** | `204` |

### 7.1 최신 버전 삭제

```http
DELETE /v1/secret/data/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```

**Response** `204`. 이후 최신 버전을 조회하면 `404`이며, 바디에 삭제 시각이 들어 있습니다.

```json
{"data":{"data":null,"metadata":{"version":4,"deletion_time":"2026-10-23T06:05:11.383657Z","destroyed":false,"custom_metadata":{"...":"..."}}}}
```

> 최신 버전만 삭제해도 **이전 버전으로 돌아가지 않습니다.** 최신 조회는 404가 됩니다. 이전 값을 다시 쓰려면 그 버전을 조회해서 새로 저장하거나 7.3절로 복구하세요.

### 7.2 지정 버전 삭제

```http
POST /v1/secret/delete/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{"versions":[1]}
```

**Response** `204`. 메타데이터의 `versions.1.deletion_time`과 `deleted_by`가 채워집니다.

### 7.3 복구 (undelete)

```http
POST /v1/secret/undelete/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{"versions":[4]}
```

**Response** `204`. 삭제(soft)된 버전만 복구됩니다. 영구 삭제된 버전은 복구되지 않습니다.

### 7.4 영구 삭제 (destroy)

```http
POST /v1/secret/destroy/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
Content-Type: application/json

{"versions":[2]}
```

**Response** `204`. 이후 그 버전을 조회하면 `404`, `destroyed: true`입니다. 메타데이터에는 버전 기록이 남습니다.

```json
{"data":{"data":null,"metadata":{"version":2,"deletion_time":"","destroyed":true,"...":"..."}}}
```

### 7.5 전체 삭제

```http
DELETE /v1/secret/metadata/sample-app/doc-sample
X-Vault-Namespace: team-a
X-Vault-Token: (토큰)
```

**Response** `204`. 메타데이터와 모든 버전이 지워지며, 이후 조회는 `404 {"errors":[]}`입니다.

---

## 8. 메타데이터 규칙 정리

| 항목 | 내용 |
| --- | --- |
| 저장 위치 | `secret/metadata/{path}` — 값(`secret/data/{path}`)과 별도 |
| 값 저장 요청에 포함 | 불가 (무시되고 warning) |
| 버전 관리 | 안 됨. 시크릿당 현재 값 하나. 어떤 버전을 조회해도 현재 값이 나옴 |
| 저장 | `POST metadata` — `custom_metadata`를 통째로 교체 |
| 일부 변경 | `PATCH metadata` (`application/merge-patch+json`) — 병합, `null`은 삭제 |
| 조회 | `GET data`(요약: `custom_metadata`만) / `GET metadata`(전체: 버전 목록, 설정, 작업 주체) |
| 제약 | 키 최대 64개, 키 128자, 값 512자, 값은 문자열 |
| 노출 범위 | `metadata/` `read` 또는 `list` 권한만 있어도 볼 수 있음 → **비밀 값 금지** |
| 삭제 | 전체 삭제(`DELETE metadata`) 때 함께 지워짐 |

---

## 9. 오류 정리

| 상태 | 원인 | 메시지 |
| --- | --- | --- |
| 400 | CAS 불일치 | `check-and-set parameter did not match the current version` |
| 400 | `custom_metadata` 제약 위반 | `custom_metadata validation failed: ...` |
| 400 | Certificate 로그인/갱신에 인증서 없음 | `client certificate must be supplied` |
| 400 | 인증서 제약 불일치 | `failed to match all constraints for this login certificate` |
| 400 | AppRole secret-id 오류 | `invalid role or secret ID` |
| 403 | 정책에 없는 작업/경로, 토큰 무효, 토큰과 다른 네임스페이스로 요청 | `permission denied` (+ `invalid token`) |
| 404 | 경로 없음, 삭제/영구 삭제된 버전, PATCH 대상 없음 | `{"errors":[]}` 또는 `data: null` 바디 |
| 415 | PATCH Content-Type 오류 | `PATCH requires Content-Type of application/merge-patch+json` |

---

## 10. 앱에 반영할 때의 변경점

현재 앱은 AppRole 로그인, KV 최신 조회, KV 저장만 구현되어 있습니다. 이 문서의 기능을 앱에 넣으려면 아래를 바꿔야 합니다.

### 10.1 코드 변경

| 대상 | 변경 |
| --- | --- |
| `VaultHttpClient` | `patch(path, token, body)`(Content-Type `application/merge-patch+json`), `delete(path, token)`, `list(path, token)`(`GET ?list=true`) 추가. Certificate 방식이면 `HttpClient`를 클라이언트 인증서 `SSLContext`로 생성 |
| `KvSecretClient` | `read(path, version)`, `write(path, data, cas)`, `patch(path, data)`, `readMetadata`, `writeMetadata`(POST), `patchMetadata`(PATCH), `deleteLatest`, `deleteVersions`, `undelete`, `destroy`, `deleteAll`, `list` 추가 |
| `KvSecret` | `customMetadata`, `createdTime` 필드 추가 |
| 인증 | `CertAuthenticator`(`POST auth/cert/login`) 추가, `TokenManager`가 AppRole/Certificate 중 하나를 쓰도록 인터페이스로 분리. secret-id 관련 기능(`SecretIdFileWatcher`, `SecretIdLookup`)은 AppRole일 때만 사용 |
| `TokenRenewer` | Certificate 방식이면 같은 `SSLContext`의 `HttpClient`로 `renew-self` 호출 (인증서 필수) |
| 설정 | `VAULT_AUTH_METHOD=approle\|cert`, `VAULT_CERT_MOUNT`, `VAULT_CERT_ROLE`, `VAULT_CLIENT_P12`, `VAULT_CLIENT_P12_PASSWORD`, `VAULT_CACERT` |
| 네임스페이스 | `VAULT_NAMESPACE` 지원은 이미 있음 (`VaultHttpClient.newRequest`가 모든 요청에 헤더 추가). 새로 추가하는 `patch`/`delete`/`list`도 `newRequest`를 거치게 해서 헤더가 빠지지 않게 할 것 |
| 정책 | 2.4절 정책 (필요한 capability만). 네임스페이스를 쓰면 그 네임스페이스 안에 작성 |

### 10.2 앱 API 확장안

| 앱 API | 바디 / 파라미터 | 내부 Vault 호출 |
| --- | --- | --- |
| `GET /v1/kv/{path}?version=N` | - | `GET data` → 응답에 `metadata`(`custom_metadata`, `created_time`, `version`) 포함 |
| `PUT /v1/kv/{path}` (저장·업데이트) | `{"data":{...}, "custom_metadata":{...}, "cas":N}` | `POST data` → `custom_metadata`가 있으면 `POST metadata` |
| `PATCH /v1/kv/{path}` (변경) | `{"data":{...}, "custom_metadata":{...}}` (`null`은 삭제) | `PATCH data`(data가 있을 때) → `PATCH metadata`(custom_metadata가 있을 때) |
| `DELETE /v1/kv/{path}` | `?versions=1,2` 없으면 최신 버전, `&destroy=true`면 영구 삭제, `?all=true`면 전체 삭제 | `DELETE data` / `POST delete` / `POST destroy` / `DELETE metadata` |
| `POST /v1/kv/{path}/undelete` | `{"versions":[...]}` | `POST undelete` |
| `GET /v1/kv-metadata/{path}` | - | `GET metadata` |
| `GET /v1/kv-list/{path}` | - | `GET metadata?list=true` |

응답 예 (`GET /v1/kv/sample-app/doc-sample`):

```json
{
  "path": "sample-app/doc-sample",
  "version": 4,
  "data": { "api_key": "def456", "retry": 7 },
  "metadata": {
    "created_time": "2026-10-23T06:05:11.196018Z",
    "custom_metadata": { "app": "sample-app", "owner": "platform-team", "rotation": "30d", "updated_by": "ops" }
  }
}
```

> 앱 API는 인증이 없고 127.0.0.1에만 바인딩되어 있습니다. 삭제·영구 삭제를 열 때는 앱 API 인증이나 별도 바인딩을 먼저 검토하세요. 어떤 작업이 실제로 허용되는지는 Vault 정책이 최종 결정합니다.
