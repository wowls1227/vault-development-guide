package com.example.vault.worker;

import com.example.vault.kv.KvSecret;
import com.example.vault.kv.KvSecretClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * KV 시크릿을 주기적으로 읽는 백그라운드 워커. (선택 기능, WORKER_THREADS 기본값 0 = 사용 안 함)
 *
 * <p>API 요청과 별개로 부하를 걸어 두고, 토큰 갱신/재로그인/secret-id 교체 중에도 조회가 끊기지 않는지
 * 확인하는 용도다. 여러 개가 스레드 풀에서 동시에 돌면서 같은 TokenManager 의 토큰을 공유한다.
 */
public final class SecretWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SecretWorker.class);

    private final KvSecretClient reader;
    private final String path;
    private final Duration interval;

    public SecretWorker(KvSecretClient reader, String path, Duration interval) {
        this.reader = reader;
        this.path = path;
        this.interval = interval;
    }

    @Override
    public void run() {
        // 스레드 풀 shutdownNow() 가 인터럽트를 걸면 루프를 빠져나간다.
        while (!Thread.currentThread().isInterrupted()) {
            try {
                KvSecret secret = reader.read(path);
                log.info("조회 성공 version={} data={}", secret.version(), mask(secret.data()));
            } catch (Exception e) {
                // 한 번 실패해도 워커는 죽지 않고 다음 주기에 다시 시도한다.
                log.error("조회 실패: {}", e.getMessage());
            }

            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("워커 종료");
    }

    /** 로그에 시크릿 값이 그대로 찍히지 않도록 password/secret/token 이 들어간 키는 마스킹한다. */
    private static String mask(Map<String, Object> data) {
        return data.entrySet().stream()
                .map(e -> e.getKey() + "=" + (isSensitive(e.getKey()) ? "****" : e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private static boolean isSensitive(String key) {
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("secret") || k.contains("token");
    }
}
