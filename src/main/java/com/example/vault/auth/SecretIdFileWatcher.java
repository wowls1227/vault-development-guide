package com.example.vault.auth;

import com.example.vault.client.VaultException;
import com.example.vault.client.VaultHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 오케스트레이터(배포 파이프라인, CI/CD 등 신뢰된 주체)가 전달하는 secret-id 파일을 감시한다.
 *
 * <p><b>Trusted Orchestrator 패턴</b>
 * <ul>
 *   <li>앱은 secret-id 를 발급/폐기하지 않는다. 앱 정책에는 secret-id 관련 권한이 전혀 없다.
 *       따라서 앱 토큰이 유출되어도 그 토큰으로 새 secret-id 를 만들 수 없다.</li>
 *   <li>오케스트레이터가 주기적으로(또는 배포 시) 새 secret-id 를 발급해 이 파일에 원자적으로 써 넣는다.</li>
 *   <li>앱은 파일 내용이 바뀌었는지 주기적으로 확인하고, 바뀌었으면 TokenManager 에 교체를 요청한다.
 *       TokenManager 는 로그인하지 않고 secret-id/lookup 으로 유효한지 확인한 뒤 보관한다. (토큰은 그대로)</li>
 *   <li>앱은 이 파일을 읽기만 한다.</li>
 * </ul>
 *
 * <p><b>파일 형식</b> (VAULT_SECRET_ID_WRAPPED 로 선택)
 * <ul>
 *   <li>false: secret-id 원문</li>
 *   <li>true : response-wrapping 토큰. 앱이 {@code sys/wrapping/unwrap} 으로 풀어서 secret-id 를 얻는다.
 *       wrapping 토큰은 한 번만 풀 수 있으므로, 전달 도중 누군가 먼저 열어 봤다면 앱의 unwrap 이 실패해 바로 드러난다.
 *       또 unwrap 전에 생성 경로(creation_path)를 확인해, 다른 API 로 만든 wrapping 토큰을 거부한다.</li>
 * </ul>
 *
 * <p>WatchService 대신 폴링을 쓰는 이유: 오케스트레이터가 임시 파일 + rename 으로 원자적 교체를 하거나,
 * k8s secret 볼륨처럼 심볼릭 링크가 바뀌는 경우에도 OS 와 무관하게 확실히 감지하기 위함.
 */
public final class SecretIdFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(SecretIdFileWatcher.class);

    private final VaultHttpClient client;
    private final ScheduledExecutorService scheduler;
    private final Path file;
    private final boolean wrapped;
    private final Duration pollInterval;
    /** wrapping 토큰이 만들어졌어야 하는 API 경로 (이 경로가 아니면 위조/오전달로 보고 거부) */
    private final String expectedCreationPath;

    /** 마지막으로 처리한 파일 내용. 같은 내용이면 다시 처리하지 않는다. (스케줄러 스레드에서만 접근) */
    private String lastContent;

    public SecretIdFileWatcher(VaultHttpClient client,
                               ScheduledExecutorService scheduler,
                               Path file,
                               boolean wrapped,
                               Duration pollInterval,
                               String appRoleMount,
                               String roleName) {
        this.client = client;
        this.scheduler = scheduler;
        this.file = file;
        this.wrapped = wrapped;
        this.pollInterval = pollInterval;
        this.expectedCreationPath = "auth/" + appRoleMount + "/role/" + roleName + "/secret-id";
    }

    /**
     * 앱 기동 시 최초 secret-id 를 읽는다. 실패하면 예외를 던져 기동을 중단시킨다.
     * (wrapped 모드에서 이미 사용된 wrapping 토큰이 남아 있다면 여기서 실패한다 → 오케스트레이터가 새로 전달해야 함)
     */
    public SecretIdCredential loadInitial() {
        String content = readFile();
        if (content == null || content.isEmpty()) {
            throw new IllegalStateException("secret-id 파일이 없거나 비어 있습니다: " + file);
        }
        SecretIdCredential cred = resolve(content);
        lastContent = content;
        log.info("최초 secret-id 로드: {} (file={}, wrapped={})", cred, file, wrapped);
        return cred;
    }

    /** 파일 변경 감시 시작. 이전 확인이 끝난 시점부터 pollInterval 후에 다음 확인 */
    public void start(TokenManager tokenManager) {
        long ms = pollInterval.toMillis();
        scheduler.scheduleWithFixedDelay(() -> checkSafely(tokenManager), ms, ms, TimeUnit.MILLISECONDS);
        log.info("secret-id 파일 감시 시작: file={}, interval={}", file, pollInterval);
    }

    /** 스케줄러용 래퍼. 예외가 스케줄러 밖으로 나가면 이후 실행이 전부 취소되므로 여기서 모두 잡는다. */
    private void checkSafely(TokenManager tokenManager) {
        try {
            check(tokenManager);
        } catch (Exception e) {
            log.error("새 secret-id 적용 실패 (기존 secret-id 유지): {}", e.getMessage());
        }
    }

    /** 파일 내용이 바뀌었으면 새 secret-id 로 교체한다. */
    private void check(TokenManager tokenManager) {
        String content = readFile();
        if (content == null || content.isEmpty() || content.equals(lastContent)) {
            return; // 파일 없음(교체 중일 수 있음) / 변경 없음
        }
        // 실패하더라도 같은 내용으로 계속 재시도하지 않도록 먼저 기록한다.
        // (특히 wrapping 토큰은 한 번 unwrap 하면 다시 못 쓴다. 다음 전달을 기다린다)
        lastContent = content;

        log.info("secret-id 파일 변경 감지");
        SecretIdCredential cred = resolve(content);
        tokenManager.switchSecretId(cred); // lookup 으로 확인 → 유효하면 보관 (토큰 유지)
    }

    /** 파일 내용을 secret-id 로 변환한다. wrapped 모드면 unwrap 한다. */
    private SecretIdCredential resolve(String content) {
        return wrapped ? unwrap(content) : SecretIdCredential.unverified(content);
    }

    /**
     * wrapping 토큰을 풀어 secret-id 를 얻는다.
     *
     * <ol>
     *   <li>{@code sys/wrapping/lookup}: 토큰을 소모하지 않고 생성 경로를 확인한다. (인증 불필요)</li>
     *   <li>{@code sys/wrapping/unwrap}: wrapping 토큰 자체를 X-Vault-Token 으로 보내 원래 응답을 받는다. (1회용)</li>
     * </ol>
     */
    private SecretIdCredential unwrap(String wrappingToken) {
        JsonNode info;
        try {
            info = client.post("sys/wrapping/lookup", null, Map.of("token", wrappingToken)).path("data");
        } catch (VaultException e) {
            throw new IllegalStateException("wrapping 토큰이 유효하지 않습니다(만료되었거나 이미 사용됨 → 전달 과정 점검 필요): "
                    + e.getMessage());
        }
        String creationPath = info.path("creation_path").asText();
        if (!expectedCreationPath.equals(creationPath)) {
            throw new IllegalStateException("wrapping 토큰 생성 경로가 다릅니다. expected=" + expectedCreationPath
                    + ", actual=" + creationPath);
        }

        JsonNode data = client.post("sys/wrapping/unwrap", wrappingToken, null).path("data");
        // 만료 시각 등은 TokenManager 가 secret-id/lookup 으로 확인한다.
        return SecretIdCredential.unverified(data.path("secret_id").asText());
    }

    /** 파일을 읽는다. 없거나 읽을 수 없으면 null */
    private String readFile() {
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            log.warn("secret-id 파일을 읽을 수 없습니다: {} ({})", file, e.getMessage());
            return null;
        }
    }
}
