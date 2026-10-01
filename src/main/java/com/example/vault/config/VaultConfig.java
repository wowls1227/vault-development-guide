package com.example.vault.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 애플리케이션 설정 값 모음.
 *
 * 모든 값은 환경변수에서 읽는다. (scripts/setup-vault.sh 가 만들어 주는 approle.env 를 source 해서 사용)
 * 
 *   VAULT_ADDR                  : Vault 주소 (기본 http://127.0.0.1:8200)
 *   VAULT_NAMESPACE             : Enterprise 네임스페이스 (OSS 에서는 비워둔다)
 *   VAULT_APPROLE_MOUNT         : AppRole auth method 마운트 경로 (기본 approle)
 *   VAULT_ROLE_NAME             : AppRole 역할 이름 (wrapping 토큰 생성 경로 검증에 사용)
 *   VAULT_ROLE_ID               : role-id (고정 값, 앱 배포 시 주입)
 *   VAULT_SECRET_ID_FILE        : 오케스트레이터가 secret-id 를 넣어 주는 파일 (권장). 앱은 읽기만 하고,
 *                                     내용이 바뀌면 새 secret-id 로 교체한다.
 *   VAULT_SECRET_ID             : 파일 대신 환경변수로 받는 secret-id. 재전달이 불가능하므로 테스트용
 *   VAULT_SECRET_ID_WRAPPED     : true 면 파일 내용을 secret-id 가 아닌 response-wrapping 토큰으로 보고 unwrap 한다.
 *   SECRET_ID_FILE_POLL_SEC     : secret-id 파일 변경 확인 주기(초)
 *   VAULT_KV_MOUNT              : KV v2 엔진 마운트 경로 (기본 secret)
 *   VAULT_KV_PATH               : 읽어올 시크릿 경로 (기본 sample-app/config)
 *   TOKEN_RENEW_INTERVAL_SEC    : 토큰 갱신(renew-self) 주기(초). token_ttl 보다 충분히 짧게
 *   OLD_TOKEN_REVOKE_GRACE_SEC  : 토큰 교체 후 이전 토큰을 폐기하기까지 대기 시간(초)
 *   API_BIND_ADDR / API_PORT    : KV API 서버 바인딩 주소/포트 (기본 127.0.0.1:8080)
 *   API_THREADS                 : API 요청 처리 스레드 수
 *   WORKER_THREADS              : 백그라운드로 KV 를 읽는 워커 스레드 수 (기본 0 = 사용 안 함)
 *   WORKER_READ_INTERVAL_MS     : 워커 1개가 KV 를 읽는 주기(ms)
 *   RUN_DURATION_SEC            : 앱 실행 시간(초). 0 이면 Ctrl+C 전까지 계속 실행
 * 
 */
public record VaultConfig(
        String vaultAddr,
        String namespace,
        String appRoleMount,
        String roleName,
        String roleId,
        String envSecretId,
        Path secretIdFile,
        boolean secretIdWrapped,
        Duration secretIdPollInterval,
        String kvMount,
        String kvPath,
        Duration tokenRenewInterval,
        Duration oldTokenRevokeGrace,
        String apiBindAddr,
        int apiPort,
        int apiThreads,
        int workerThreads,
        Duration workerReadInterval,
        Duration runDuration) {

    /** 환경변수에서 설정을 읽어 VaultConfig 를 생성한다. 필수 값이 없으면 즉시 실패시킨다. */
    public static VaultConfig fromEnv() {
        String secretIdFileEnv = env("VAULT_SECRET_ID_FILE", null);
        Path secretIdFile = secretIdFileEnv == null ? null : Path.of(secretIdFileEnv);
        String envSecretId = env("VAULT_SECRET_ID", null);
        boolean wrapped = Boolean.parseBoolean(env("VAULT_SECRET_ID_WRAPPED", "false"));

        // secret-id 는 파일(오케스트레이터 전달) 우선. 파일 내용은 앱 기동 시 SecretIdFileWatcher 가 읽는다.
        if (secretIdFile == null && envSecretId == null) {
            throw new IllegalStateException("VAULT_SECRET_ID_FILE 또는 VAULT_SECRET_ID 를 지정해야 합니다.");
        }
        if (secretIdFile == null && wrapped) {
            throw new IllegalStateException("VAULT_SECRET_ID_WRAPPED=true 는 VAULT_SECRET_ID_FILE 과 함께 사용해야 합니다.");
        }

        return new VaultConfig(
                stripTrailingSlash(env("VAULT_ADDR", "http://127.0.0.1:8200")),
                env("VAULT_NAMESPACE", null),
                env("VAULT_APPROLE_MOUNT", "approle"),
                env("VAULT_ROLE_NAME", "sample-app"),
                required("VAULT_ROLE_ID"),
                envSecretId,
                secretIdFile,
                wrapped,
                Duration.ofSeconds(Long.parseLong(env("SECRET_ID_FILE_POLL_SEC", "5"))),
                env("VAULT_KV_MOUNT", "secret"),
                env("VAULT_KV_PATH", "sample-app/config"),
                Duration.ofSeconds(Long.parseLong(env("TOKEN_RENEW_INTERVAL_SEC", "20"))),
                Duration.ofSeconds(Long.parseLong(env("OLD_TOKEN_REVOKE_GRACE_SEC", "5"))),
                env("API_BIND_ADDR", "127.0.0.1"),
                Integer.parseInt(env("API_PORT", "8080")),
                Integer.parseInt(env("API_THREADS", "8")),
                Integer.parseInt(env("WORKER_THREADS", "0")),
                Duration.ofMillis(Long.parseLong(env("WORKER_READ_INTERVAL_MS", "2000"))),
                Duration.ofSeconds(Long.parseLong(env("RUN_DURATION_SEC", "0"))));
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static String required(String key) {
        String v = env(key, null);
        if (v == null) {
            throw new IllegalStateException("필수 환경변수 누락: " + key);
        }
        return v;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 로그에 설정을 출력할 때 role-id / secret-id 가 노출되지 않도록 마스킹한다. */
    @Override
    public String toString() {
        return "VaultConfig{addr=" + vaultAddr
                + ", namespace=" + namespace
                + ", appRoleMount=" + appRoleMount
                + ", roleName=" + roleName
                + ", secretIdFile=" + secretIdFile
                + ", secretIdWrapped=" + secretIdWrapped
                + ", secretIdPollInterval=" + secretIdPollInterval
                + ", kv=" + kvMount + "/" + kvPath
                + ", tokenRenewInterval=" + tokenRenewInterval
                + ", api=" + apiBindAddr + ":" + apiPort + "(threads=" + apiThreads + ")"
                + ", workers=" + workerThreads
                + ", readInterval=" + workerReadInterval
                + ", runDuration=" + runDuration + "}";
    }
}
