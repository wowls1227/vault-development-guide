package com.example.vault;

import com.example.vault.api.KvApiServer;
import com.example.vault.auth.AppRoleAuthenticator;
import com.example.vault.auth.SecretIdCredential;
import com.example.vault.auth.SecretIdFileWatcher;
import com.example.vault.auth.SecretIdLookup;
import com.example.vault.auth.TokenManager;
import com.example.vault.auth.TokenRenewer;
import com.example.vault.client.VaultHttpClient;
import com.example.vault.config.VaultConfig;
import com.example.vault.kv.KvSecretClient;
import com.example.vault.util.NamedThreadFactory;
import com.example.vault.worker.SecretWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 애플리케이션 진입점. 각 컴포넌트를 조립하고 스레드를 띄운 뒤, API 요청을 기다리며 대기한다.
 *
 * <pre>
 *  HTTP 클라이언트 ──▶ [api-1..N] KvApiServer ──▶ KvSecretClient ──┐
 *  (선택) [worker-1..N] SecretWorker ─────────▶ KvSecretClient ──┤
 *                                                                ├─▶ TokenManager (공유 토큰, 403 시 재로그인)
 *  [vault-scheduler] TokenRenewer        (토큰 TTL 연장) ──────────┤        │
 *  [vault-scheduler] SecretIdFileWatcher (secret-id 파일 감시) ───┘        ▼
 *        ▲                                                       AppRoleAuthenticator ──▶ Vault
 *        │ secret-id 파일 (앱은 읽기만 함)                                                  ▲
 *  오케스트레이터 (CI/CD 등) ── 새 secret-id 발급 (앱에는 발급 권한 없음) ─────────────────────┘
 * </pre>
 *
 * <p>스레드 구성
 * <ul>
 *   <li>api-N             : HTTP API 요청 처리 (고정 크기 스레드 풀)</li>
 *   <li>vault-scheduler-N : 토큰 갱신, secret-id 파일 감시, 이전 토큰 지연 폐기</li>
 *   <li>worker-N          : (선택) KV 를 주기적으로 읽는 백그라운드 워커</li>
 * </ul>
 */
public final class App {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException, IOException {
        VaultConfig config = VaultConfig.fromEnv();
        log.info("설정: {}", config);

        // --- 1) 공용 컴포넌트 생성 ---
        VaultHttpClient client = new VaultHttpClient(config);
        AppRoleAuthenticator authenticator = new AppRoleAuthenticator(client, config.appRoleMount());

        // 토큰 갱신, secret-id 파일 감시, 이전 토큰 지연 폐기를 처리할 스케줄러.
        // API 요청 스레드와 분리해서 요청이 몰려도 갱신 작업이 밀리지 않게 한다.
        ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(2, new NamedThreadFactory("vault-scheduler"));

        // --- 2) 최초 secret-id 확보 (오케스트레이터가 전달한 파일 우선) ---
        SecretIdFileWatcher watcher = null;
        SecretIdCredential initialCredential;
        if (config.secretIdFile() != null) {
            watcher = new SecretIdFileWatcher(client, scheduler, config.secretIdFile(), config.secretIdWrapped(),
                    config.secretIdPollInterval(), config.appRoleMount(), config.roleName());
            initialCredential = watcher.loadInitial();
        } else {
            log.warn("VAULT_SECRET_ID 환경변수 사용: 새 secret-id 를 전달받을 수 없어 secret-id 만료 후에는 재로그인이 불가합니다.");
            initialCredential = SecretIdCredential.unverified(config.envSecretId());
        }

        // --- 3) 최초 로그인 (이후로는 토큰을 갱신하며 사용, 로그인은 장애 복구 때만) ---
        SecretIdLookup secretIdLookup = new SecretIdLookup(client, config.appRoleMount(), config.roleName());
        TokenManager tokenManager = new TokenManager(
                client, authenticator, secretIdLookup, config.roleId(), initialCredential,
                scheduler, config.oldTokenRevokeGrace());
        tokenManager.initialize();

        // --- 4) 토큰 주기 갱신 시작 ---
        new TokenRenewer(client, tokenManager, scheduler, config.tokenRenewInterval()).start();

        // --- 5) secret-id 파일 감시 시작 (오케스트레이터가 새 secret-id 를 넣으면 교체) ---
        if (watcher != null) {
            watcher.start(tokenManager);
        }

        // --- 6) KV API 서버 시작 (요청 대기) ---
        KvSecretClient kv = new KvSecretClient(client, tokenManager, config.kvMount());
        KvApiServer api = new KvApiServer(config.apiBindAddr(), config.apiPort(), config.apiThreads(), kv, tokenManager);
        api.start();

        // --- 7) (선택) 백그라운드 워커 시작 ---
        ExecutorService workers = null;
        if (config.workerThreads() > 0) {
            workers = Executors.newFixedThreadPool(config.workerThreads(), new NamedThreadFactory("worker"));
            for (int i = 0; i < config.workerThreads(); i++) {
                workers.submit(new SecretWorker(kv, config.kvPath(), config.workerReadInterval()));
            }
        }

        // --- 8) 종료 처리 ---
        ExecutorService workersRef = workers;
        CountDownLatch stopped = new CountDownLatch(1);
        Runnable shutdown = () -> {
            log.info("종료 시작");
            api.stop();                           // 새 요청 받지 않음, 처리 중인 요청은 잠시 기다림
            if (workersRef != null) {
                workersRef.shutdownNow();         // 워커에 인터럽트 -> 루프 종료
                try {
                    workersRef.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            scheduler.shutdownNow();              // 갱신 스케줄 중단
            tokenManager.revokeCurrent();         // 사용 중이던 토큰을 남겨두지 않도록 폐기
            log.info("종료 완료");
            stopped.countDown();
        };
        // Ctrl+C (SIGINT/SIGTERM) 로 종료할 때도 토큰을 정리하도록 shutdown hook 등록
        Thread hook = new Thread(shutdown, "shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        if (!config.runDuration().isZero()) {
            // RUN_DURATION_SEC 가 지정되면 해당 시간만큼만 실행 후 종료 (테스트용)
            Thread.sleep(config.runDuration().toMillis());
            Runtime.getRuntime().removeShutdownHook(hook);
            shutdown.run();
        } else {
            // API 요청을 기다리며 대기 (실제 처리는 api-N 스레드가 함)
            stopped.await();
        }
    }
}
