package com.example.vault.auth;

import com.example.vault.client.LoginUnavailableException;
import com.example.vault.client.VaultException;
import com.example.vault.client.VaultHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Vault 토큰 주기 갱신 담당. 스케줄러 스레드에서 주기적으로 실행된다.
 *
 * <p><b>periodic 토큰 전제</b>: role 에 token_period 를 설정하면 로그인으로 받은 토큰이 periodic 토큰이 된다.
 * period 안에 renew 하기만 하면 TTL 이 매번 period 로 다시 채워지고 max_ttl 제한도 받지 않으므로,
 * 한 번 받은 토큰을 재로그인 없이 계속 쓸 수 있다.
 *
 * <p><b>갱신 절차</b> (한 번의 renew)
 * <ol>
 *   <li>현재 토큰으로 {@code POST auth/token/renew-self} 를 호출해 TTL 을 연장한다.
 *       (default 정책에 포함된 권한이라 별도 정책이 필요 없음)</li>
 *   <li>응답의 lease_duration 으로 토큰의 만료 시각을 다시 계산해 TokenManager 에 반영한다.</li>
 *   <li>토큰이 이미 무효(403)라면 재로그인한다. (장애 복구)</li>
 *   <li>(안전장치) periodic 토큰이 아니어서 max_ttl 에 거의 도달했다면 재로그인한다.
 *       role 설정이 잘못된 경우에만 일어나며, 시작 시 경고 로그로 알려준다.</li>
 * </ol>
 *
 * <p>갱신 주기는 token_period 보다 충분히 짧아야 한다. (예: period 60s → 주기 20s, period 1h → 주기 20분)
 */
public final class TokenRenewer {

    private static final Logger log = LoggerFactory.getLogger(TokenRenewer.class);

    private final VaultHttpClient client;
    private final TokenManager tokenManager;
    private final ScheduledExecutorService scheduler;
    private final Duration interval;
    /** 연장 후 TTL 이 이 값 이하면 다음 갱신 전에 만료될 수 있으므로 재로그인한다. */
    private final long minUsefulLeaseSec;

    public TokenRenewer(VaultHttpClient client,
                        TokenManager tokenManager,
                        ScheduledExecutorService scheduler,
                        Duration interval) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.scheduler = scheduler;
        this.interval = interval;
        this.minUsefulLeaseSec = interval.plus(TokenManager.EXPIRY_MARGIN).toSeconds();
    }

    /** 주기 갱신 시작. 이전 갱신이 끝난 시점부터 interval 후에 다음 갱신 (갱신이 겹치지 않음) */
    public void start() {
        VaultToken t = tokenManager.currentToken();
        warnIfNotPeriodic(t);
        if (t != null && t.leaseDurationSec() > 0 && t.leaseDurationSec() <= minUsefulLeaseSec) {
            log.warn("토큰 갱신 주기({})가 토큰 TTL({}s)에 비해 너무 깁니다. 매 주기마다 재로그인하게 됩니다.",
                    interval, t.leaseDurationSec());
        }
        long ms = interval.toMillis();
        scheduler.scheduleWithFixedDelay(this::renewSafely, ms, ms, TimeUnit.MILLISECONDS);
        log.info("토큰 주기 갱신 시작: interval={}", interval);
    }

    /**
     * lookup-self 로 토큰이 periodic 인지 확인한다. 아니면 max_ttl 에 도달할 때마다 재로그인하게 되므로 경고한다.
     * <p>응답의 data.period: periodic 토큰이면 period(초), 아니면 없음/0
     */
    private void warnIfNotPeriodic(VaultToken t) {
        if (t == null) {
            return;
        }
        try {
            long period = client.get("auth/token/lookup-self", t.token()).path("data").path("period").asLong(0);
            if (period > 0) {
                log.info("periodic 토큰 확인: period={}s (갱신만 계속하면 재로그인 없이 사용)", period);
            } else {
                log.warn("periodic 토큰이 아닙니다. token_max_ttl 에 도달할 때마다 secret-id 로 재로그인합니다. "
                        + "role 에 token_period 를 설정하세요.");
            }
        } catch (VaultException e) {
            log.warn("토큰 정보 조회 실패: {}", e.getMessage());
        }
    }

    /** 스케줄러용 래퍼. 예외가 스케줄러 밖으로 나가면 이후 실행이 전부 취소되므로 여기서 모두 잡는다. */
    private void renewSafely() {
        try {
            renew();
        } catch (LoginUnavailableException e) {
            // 로그인 불가 상태: Vault 를 호출하지 않고 대기 중 (새 secret-id 전달 또는 lockout 해제를 기다림)
            log.warn("토큰 갱신 보류: {}", e.getMessage());
        } catch (Exception e) {
            log.error("토큰 갱신 실패 (다음 주기에 재시도): {}", e.getMessage());
        }
    }

    /** 토큰 1회 갱신 */
    public void renew() {
        VaultToken current = tokenManager.currentToken();
        if (current == null) {
            return; // 종료 중
        }

        // renew 불가 토큰이면 만료 전에 재로그인만 한다.
        if (!current.renewable()) {
            if (current.remainingSec() <= minUsefulLeaseSec) {
                log.info("갱신 불가 토큰 만료 임박 -> 재로그인");
                tokenManager.refreshIfStale(current);
            }
            return;
        }

        JsonNode res;
        try {
            res = client.post("auth/token/renew-self", current.token(), null);
        } catch (VaultException e) {
            if (e.isPermissionDenied()) {
                log.warn("토큰 무효(403) -> 재로그인: accessor={}", current.accessor());
                tokenManager.refreshIfStale(current);
                return;
            }
            throw e;
        }

        long lease = res.path("auth").path("lease_duration").asLong();
        // max_ttl 에 막혀 요청보다 짧게 연장되면 Vault 가 warnings 에 사유를 담아준다.
        JsonNode warnings = res.path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            log.info("Vault 경고: {}", warnings);
        }

        if (lease <= minUsefulLeaseSec) {
            // (안전장치) periodic 토큰이 아니어서 token_max_ttl 에 거의 도달: 연장해도 다음 주기 전에 만료되므로 새 토큰으로 교체.
            // periodic 토큰이면 lease 가 매번 period 로 채워지므로 여기에 오지 않는다.
            log.warn("토큰 max_ttl 도달 임박(남은 TTL {}s) -> 재로그인 (periodic 토큰이 아님)", lease);
            tokenManager.refreshIfStale(current);
            return;
        }

        VaultToken renewed = current.withRenewedLease(lease);
        if (tokenManager.applyRenewal(current, renewed)) {
            log.info("토큰 갱신(renew-self) 완료: {}", renewed);
        } else {
            log.info("갱신 중 토큰이 교체되어 갱신 결과는 무시: accessor={}", current.accessor());
        }
    }
}
