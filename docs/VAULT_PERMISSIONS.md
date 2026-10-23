# Vault 권한 가이드

이 문서는 샘플 앱 구성에서 **누가 Vault의 어떤 경로에 어떤 권한을 갖는지**, 그리고 **왜 그렇게 나눴는지**를 설명합니다. 권한을 추가하거나 바꿀 때 참고하세요.

- 표에 적은 권한은 모두 Vault OSS 2.1.1 dev 서버에서 `vault token capabilities`로 실제 확인한 값입니다.
- 관련 문서
  - [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md): 설계 결정 D3(앱이 secret-id를 만들지 않음), D6(lookup 검증), 8장(보안)
  - [VAULT_API_GUIDE.md](VAULT_API_GUIDE.md): 각 API의 request/response

---

## 1. Vault 권한 기본 개념

| 개념 | 설명 |
| --- | --- |
| **정책(policy)** | "어떤 경로(path)에 어떤 작업(capability)을 허용하는가"를 적은 HCL 문서입니다. 정책에 없는 경로는 모두 **거부**됩니다(deny by default). |
| **토큰과 정책** | 권한은 토큰에 붙습니다. AppRole로 로그인하면 role에 설정된 `token_policies`와 `default` 정책이 토큰에 붙습니다. |
| **경로(path)** | API URL에서 `/v1/`을 뺀 부분입니다. 예: `GET /v1/secret/data/app/config` → `secret/data/app/config` |
| **와일드카드** | `*`는 **경로 끝에서만** 쓸 수 있고, 그 접두어로 시작하는 모든 경로와 맞습니다. `+`는 경로 세그먼트 하나와 맞습니다. |

### 1.1 capability와 HTTP 메서드

| capability | 허용하는 작업 | 대응 HTTP 메서드 |
| --- | --- | --- |
| `create` | 없는 경로에 새로 쓰기 | `POST` / `PUT` (대상이 없을 때) |
| `read` | 읽기 | `GET` |
| `update` | 있는 경로에 쓰기. **대부분의 "동작" API**(로그인 외 auth API, `renew-self` 등)도 update | `POST` / `PUT` |
| `patch` | 부분 수정 | `PATCH` |
| `delete` | 삭제 | `DELETE` |
| `list` | 목록 조회 | `LIST` |
| `sudo` | root 보호 경로 접근 | - |
| `deny` | 명시적 거부. 다른 정책의 허용보다 우선 | - |

> **KV v2는 실제 API 경로에 `data/`가 들어갑니다.** CLI의 `vault kv get secret/app/config`는 `GET /v1/secret/data/app/config`를 호출합니다. 그래서 정책도 `secret/data/...`로 적어야 합니다. 정책에 `secret/app/*`라고 쓰는 것이 가장 흔한 실수입니다.

---

## 2. 주체와 역할

| 주체 | 인증 방식 | 붙는 정책 | 역할 |
| --- | --- | --- | --- |
| **앱** | AppRole (`sample-app` role) | `sample-app`, `default` | KV 읽기/저장, 전달받은 secret-id 조회, 자기 토큰 관리 |
| **오케스트레이터** | 전용 토큰 (`setup-vault.sh`가 발급, period 24h) | `sample-app-orchestrator`, `default` | 앱의 secret-id 발급 |
| **관리자** | root 토큰 (dev 전용) | `root` | 초기 구성: auth 활성화, 정책·role 작성, 잠금 해제 |

핵심 원칙은 **권한 분리**입니다.
- 앱은 시크릿을 읽을 수 있지만 **secret-id를 만들 수 없습니다.** 그래서 앱 토큰이 유출되어도, 공격자가 다시 로그인할 수단을 새로 만들 수 없습니다.
- 오케스트레이터는 secret-id를 만들 수 있지만 **시크릿을 읽을 수 없습니다.** 그래서 오케스트레이터 토큰이 유출되어도 시크릿은 노출되지 않습니다.

---

## 3. 정책 원문과 설명

`scripts/setup-vault.sh`가 아래 정책을 만듭니다.

### 3.1 앱 정책 `sample-app`

```hcl
# KV v2 시크릿 읽기/저장
path "secret/data/sample-app/*" {
  capabilities = ["create", "read", "update"]
}

# 전달받은 secret-id 가 유효한지 / 언제 만료되는지 조회
path "auth/approle/role/sample-app/secret-id/lookup" {
  capabilities = ["update"]
}
```

| 경로 | capability | 쓰는 코드 | 호출 시점 |
| --- | --- | --- | --- |
| `secret/data/sample-app/*` | `read` | `KvSecretClient.read` | `GET /v1/kv/{path}` API 요청 |
| `secret/data/sample-app/*` | `create`, `update` | `KvSecretClient.write` | `PUT`/`POST /v1/kv/{path}` (새 경로는 create, 기존 경로는 update) |
| `auth/approle/role/sample-app/secret-id/lookup` | `update` | `SecretIdLookup.lookup` | 새 secret-id를 전달받았을 때, 로그인 직후 |

**일부러 넣지 않은 권한**

| 경로 | 넣지 않은 이유 |
| --- | --- |
| `auth/approle/role/sample-app/secret-id` (발급) | 앱 토큰이 유출되면 secret-id를 새로 만들 수 있게 됩니다 |
| `secret/metadata/sample-app/*` | 버전 목록이나 삭제 같은 메타데이터 작업은 앱에 필요하지 않습니다 |
| `secret/data/sample-app/*`의 `delete` | 삭제 API를 제공하지 않습니다 |

### 3.2 오케스트레이터 정책 `sample-app-orchestrator`

```hcl
# sample-app role 의 secret-id 발급만 허용 (KV 등 시크릿 자체에는 접근 불가)
path "auth/approle/role/sample-app/secret-id" {
  capabilities = ["update"]
}
```

| 경로 | capability | 쓰는 곳 | 비고 |
| --- | --- | --- | --- |
| `auth/approle/role/sample-app/secret-id` | `update` | `scripts/deliver-secret-id.sh` | wrapping 전달(`X-Vault-Wrap-TTL`)에도 같은 권한이면 됩니다 |

### 3.3 `default` 정책 (Vault 기본 제공, 모든 토큰에 자동으로 붙음)

앱이 쓰는 부분만 발췌했습니다(`vault policy read default`).

```hcl
path "auth/token/lookup-self" { capabilities = ["read"] }
path "auth/token/renew-self"  { capabilities = ["update"] }
path "auth/token/revoke-self" { capabilities = ["update"] }
path "sys/wrapping/lookup"    { capabilities = ["update"] }
path "sys/wrapping/unwrap"    { capabilities = ["update"] }
```

| 경로 | 쓰는 코드 | 용도 |
| --- | --- | --- |
| `auth/token/lookup-self` | `TokenManager.isTokenValid`, `TokenRenewer.warnIfNotPeriodic` | 403이 토큰 무효인지 정책 거부인지 구분, periodic 확인 |
| `auth/token/renew-self` | `TokenRenewer.renew` | 토큰 주기 갱신 |
| `auth/token/revoke-self` | `TokenManager.revokeQuietly` | 이전 토큰 폐기, 종료 시 폐기 |

> `default` 정책은 관리자가 수정할 수 있습니다. 운영 Vault에서 이 세 경로가 빠져 있으면 토큰 갱신이 실패하므로, 적용하기 전에 `vault policy read default`로 확인하세요.

### 3.4 토큰 없이 호출하는 API

| API | 쓰는 코드 | 인증 수단 |
| --- | --- | --- |
| `POST auth/approle/login` | `AppRoleAuthenticator.login` | 요청 바디의 role-id + secret-id |
| `POST sys/wrapping/lookup` | `SecretIdFileWatcher.unwrap` | 요청 바디의 wrapping 토큰 |
| `POST sys/wrapping/unwrap` | `SecretIdFileWatcher.unwrap` | wrapping 토큰 자체를 `X-Vault-Token`으로 사용 |

---

## 4. 실제 권한 확인 결과

`vault token capabilities <토큰> <경로>`로 확인한 결과입니다.

| 경로 | 앱 토큰 | 오케스트레이터 토큰 |
| --- | --- | --- |
| `secret/data/sample-app/config` | **create, read, update** | deny |
| `secret/data/sample-app` (접두어 자체) | deny | deny |
| `secret/metadata/sample-app/config` | deny | deny |
| `secret/data/other-app/x` | deny | deny |
| `auth/approle/role/sample-app/secret-id` (발급) | **deny** | **update** |
| `auth/approle/role/sample-app/secret-id/lookup` | **update** | deny |
| `auth/approle/role/sample-app/secret-id/destroy` | deny | deny |
| `auth/approle/role/sample-app/role-id` | deny | deny |
| `auth/approle/role/sample-app` (role 설정) | deny | deny |
| `auth/token/lookup-self` · `renew-self` · `revoke-self` | read · update · update | read · update · update |
| `sys/wrapping/lookup` · `unwrap` | update · update | update · update |
| `sys/policies/acl/sample-app` (정책 수정) | deny | deny |

**읽는 법**
- `secret/data/sample-app/*`는 **`secret/data/sample-app` 경로 자체와는 맞지 않습니다**(`*` 앞의 `/`까지 포함한 접두어이기 때문). 앱 API도 `sample-app/` 아래의 경로만 쓸 수 있습니다.
- 앱도 오케스트레이터도 role 설정, 정책, role-id를 읽거나 바꿀 수 없습니다.

직접 확인하는 방법은 다음과 같습니다.

```bash
vault token capabilities <토큰> secret/data/sample-app/config
# 앱 토큰 대신 accessor 로 확인하려면 (관리자):
vault token capabilities -accessor <accessor> secret/data/sample-app/config
```

---

## 5. 권한을 바꿀 때 반영되는 시점

**이 앱은 periodic 토큰을 갱신하며 계속 쓰고 재로그인하지 않습니다.** 그래서 무엇을 바꾸느냐에 따라 반영 시점이 다릅니다. 아래 두 경우 모두 실제로 확인했습니다.

| 바꾸는 것 | 예 | 기존 토큰 | 새 토큰 |
| --- | --- | --- | --- |
| **정책 내용** (`vault policy write sample-app ...`) | 경로나 capability 추가·삭제 | **즉시 반영** | 반영 |
| **role의 정책 목록** (`token_policies`) | 정책 이름 추가·삭제 | **반영 안 됨** (renew해도 그대로) | 반영 |

- 권한을 넓히거나 좁힐 때는 **정책 내용을 수정**하세요. 앱을 재시작하지 않아도 바로 적용됩니다.
- role에 정책을 새로 붙였다면 **앱을 재시작**해야 합니다. 그래야 새로 로그인해서 새 정책 목록이 붙은 토큰을 받습니다. 관리자가 앱 토큰을 폐기해도 되는데, 그러면 앱이 장애 복구 경로로 재로그인합니다.

---

## 6. 권한 관련 오류와 증상

| 증상 | 원인 | 확인 / 조치 |
| --- | --- | --- |
| API가 `403`, 앱 로그에 재로그인 없음 | 요청한 KV 경로가 앱 정책에 없음 (정상 거부) | 필요한 경로라면 정책에 추가 |
| 정책에 넣었는데도 KV가 `403` | 정책 경로에 `data/` 누락 (`secret/sample-app/*`) | `secret/data/sample-app/*`로 수정 |
| `secret/data/sample-app`(접두어 자체)만 `403` | `*`는 `sample-app/` 아래에만 맞음 | 그 경로도 필요하면 따로 추가 |
| 새 secret-id 적용 실패: `secret-id 조회 권한이 없습니다` | 앱 정책에 `secret-id/lookup` 없음 | 3.1절 정책 적용 |
| 시작 시 `secret-id 조회 실패(만료 시각을 모름)` WARN | 위와 같음. 로그인은 되지만 만료 시각을 모름 | 3.1절 정책 적용 |
| 20초마다 `토큰 무효(403) -> 재로그인` 반복 | `default` 정책에서 `renew-self`가 빠짐 (갱신이 403이라 토큰이 무효로 보임) | `vault policy read default` 확인 |
| 오케스트레이터 secret-id 발급 `403`, 또는 `오케스트레이터 토큰 갱신 실패` | 오케스트레이터 정책 누락, 또는 토큰 만료. `deliver-secret-id.sh`는 전달할 때마다 토큰을 갱신하지만, 24시간(period) 넘게 전달하지 않으면 만료됩니다 | 정책 확인. 만료되었으면 `setup-vault.sh`로 새 토큰 발급 |
| 로그인 `403 permission denied` | 권한 문제가 아니라 **로그인 잠김**(user lockout) | `vault read sys/locked-users` ([DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) 10.3절) |
| role에 정책을 추가했는데 앱에 적용 안 됨 | 기존 토큰에는 반영되지 않음 (5장) | 앱 재시작 |

---

## 7. 운영 권장 사항

| 항목 | 권장 |
| --- | --- |
| 최소 권한 | 앱마다 정책을 따로 두고, 앱이 쓰는 KV 경로만 허용합니다. 쓰기가 필요 없으면 `read`만 줍니다. |
| 경로 설계 | `secret/data/<앱 이름>/*`처럼 앱별 접두어로 나누면 정책을 단순하게 유지할 수 있습니다. |
| 환경 분리 | 개발·검증·운영 Vault(또는 Enterprise 네임스페이스)를 나누고, 정책도 환경별로 둡니다. |
| 오케스트레이터 | 발급 대상 role 하나만 허용합니다. 여러 앱을 담당한다면 `auth/approle/role/+/secret-id`처럼 넓히기보다 role마다 경로를 나열하는 것이 안전합니다. 이전 secret-id를 정리하려면 `auth/approle/role/<role>/secret-id-accessor/destroy`의 `update`를 추가합니다. |
| 오케스트레이터 인증 | 운영에서는 정적 토큰 대신 CI/CD 플랫폼에 맞는 auth method(Kubernetes, JWT/OIDC 등)로 인증하게 합니다. |
| 위치 제한 | role에 `secret_id_bound_cidrs`(로그인 위치), `token_bound_cidrs`(토큰 사용 위치)를 설정합니다. |
| root 토큰 | 이 샘플은 dev 구성을 위해 root를 씁니다. **운영에서는 root 토큰을 일상 작업에 쓰지 않습니다.** 초기 구성 뒤 폐기하고, 관리 작업에는 필요한 권한만 가진 관리자 정책을 씁니다. |
| `default` 정책 | 이 앱은 `auth/token/*-self` 세 경로에 의존합니다. `default` 정책을 손볼 때 이 경로를 지우지 않도록 합니다. |
| 감사 | 운영에서는 audit device(`vault audit enable file ...`)를 켜서 누가 어떤 경로를 호출했는지 남깁니다. |
| 변경 절차 | 정책은 코드처럼 버전 관리하고(Terraform Vault provider 등), 바꾼 뒤에는 4장의 `vault token capabilities`로 확인합니다. |
