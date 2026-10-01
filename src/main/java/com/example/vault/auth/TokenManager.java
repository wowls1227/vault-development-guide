package com.example.vault.auth;

import com.example.vault.client.LoginUnavailableException;
import com.example.vault.client.VaultException;
import com.example.vault.client.VaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 멀티쓰레드 환경에서 Vault 토큰과 secret-id 를 관리하는 중심 클래스.
 *
 * <p><b>기본 구조</b>: 한 번 로그인해서 받은 periodic 토큰을 TokenRenewer 가 계속 갱신하며 사용한다.
 * secret-id 로 로그인하는 것은 아래 두 경우뿐이다.
 * <ol>
 *   <li>앱 시작 (토큰이 아직 없음)</li>
 *   <li>장애 복구 (토큰이 강제 폐기되었거나, 갱신을 놓쳐 만료됨)</li>
 * </ol>
 * 새 secret-id 를 전달받아도 로그인하지 않는다. secret-id/lookup 으로 유효한지 확인한 뒤 보관만 하고,
 * 다음에 로그인이 필요할 때 사용한다. 토큰은 그대로 유지된다.
 *
 * <p><b>역할 분담</b>
 * <ul>
 *   <li>TokenManager        : 현재 토큰/secret-id 보관, 로그인, 403 시 재로그인, secret-id 검증/교체</li>
 *   <li>TokenRenewer        : 주기적으로 토큰 TTL 연장(renew-self)</li>
 *   <li>SecretIdFileWatcher : 오케스트레이터가 새 secret-id 를 전달하면 감지해서 교체 요청
 *                             (앱은 secret-id 를 직접 발급하지 않는다)</li>
 *   <li>SecretIdLookup      : secret-id 가 유효한지 / 언제 만료되는지 조회</li>
 * </ul>
 *
 * <p><b>동시성 설계</b>
 * <ul>
 *   <li><b>읽기(API/워커 스레드)</b>: 현재 토큰/secret-id 를 {@link AtomicReference} 에 담아 두고 락 없이 읽는다.
 *       요청 스레드가 많아도 토큰 조회 때문에 서로 막히지 않는다.</li>
 *   <li><b>로그인 / secret-id 교체</b>: {@link ReentrantLock} 으로 한 번에 한 스레드만 수행한다.
 *       여러 스레드가 동시에 403 을 받아도 재로그인은 한 번만 일어난다(single-flight).</li>
 *   <li><b>토큰 갱신(renew)</b>: 같은 토큰의 TTL 만 바뀌므로 락 없이 compareAndSet 으로 교체한다.
 *       그 사이 재로그인으로 토큰이 바뀌었다면 CAS 가 실패하고 갱신 결과는 버린다.</li>
 *   <li><b>이전 토큰 정리</b>: 재로그인으로 토큰을 바꾼 직후 이전 토큰을 바로 폐기하면, 그 토큰으로 요청 중이던 스레드가 실패한다.
 *       그래서 grace 시간만큼 기다렸다가 이전 토큰으로 {@code revoke-self} 를 호출한다.</li>
 * </ul>
 *
 * <p><b>로그인 실패 보호</b>: Vault 는 AppRole 로그인이 연속으로 실패하면(기본 5회) role-id 를 일정 시간(기본 15분) 잠근다.
 * 잠기면 올바른 secret-id 로도 403 이 나서 회복할 수 없으므로, 실패할 것이 확실한 로그인은 Vault 에 보내지 않는다.
 * <ul>
 *   <li>secret-id 가 만료 시각을 지났으면 로그인하지 않는다. (lookup 으로 미리 알아 둔 expiration_time 기준)</li>
 *   <li>로그인이 400(secret-id 거부)이면 새 secret-id 가 올 때까지 로그인하지 않는다.</li>
 *   <li>로그인이 403(잠김 의심)이면 일정 시간 로그인하지 않는다.</li>
 * </ul>
 */
public final class TokenManager {

    private static final Logger log = LoggerFactory.getLogger(TokenManager.class);

    /** 만료까지 이 시간보다 적게 남은 토큰은 쓰지 않고 먼저 재로그인한다. */
    public static final Duration EXPIRY_MARGIN = Duration.ofSeconds(10);

    /** 로그인이 403(user lockout 의심)으로 실패하면 이 시간 동안 로그인을 시도하지 않는다. */
    public static final Duration LOCKOUT_BACKOFF = Duration.ofSeconds(60);

    private final VaultHttpClient client;
    private final AppRoleAuthenticator authenticator;
    private final SecretIdLookup secretIdLookup;
    private final String roleId;
    private final ScheduledExecutorService scheduler;
    private final Duration revokeGrace;

    /** 다음 로그인에 쓸 secret-id. 오케스트레이터가 새 것을 전달하면 SecretIdFileWatcher 가 교체한다. */
    private final AtomicReference<SecretIdCredential> credential;
    /** 현재 모든 스레드가 공유하는 클라이언트 토큰 */
    private final AtomicReference<VaultToken> token = new AtomicReference<>();
    /** 로그인 / secret-id 교체를 한 스레드만 하도록 막는 락 */
    private final ReentrantLock loginLock = new ReentrantLock();

    // 로그인 실패 보호 상태 (쓰기는 loginLock 안에서, 읽기는 /health 등에서 락 없이 하므로 volatile)
    /** 현재 secret-id 가 사용 불가(Vault 거부 / 만료). 새 secret-id 가 전달될 때까지 로그인하지 않는다. */
    private volatile boolean credentialRejected;
    /** 이 시각 전에는 로그인하지 않는다. (403 = lockout 의심 시 설정) */
    private volatile Instant loginBlockedUntil = Instant.EPOCH;

    public TokenManager(VaultHttpClient client,
                        AppRoleAuthenticator authenticator,
                        SecretIdLookup secretIdLookup,
                        String roleId,
                        SecretIdCredential initialCredential,
                        ScheduledExecutorService scheduler,
                        Duration revokeGrace) {
        this.client = client;
        this.authenticator = authenticator;
        this.secretIdLookup = secretIdLookup;
        this.roleId = roleId;
        this.credential = new AtomicReference<>(initialCredential);
        this.scheduler = scheduler;
        this.revokeGrace = revokeGrace;
    }

    /**
     * 앱 시작 시 최초 로그인. 실패하면 예외를 그대로 던져 앱 기동을 중단시킨다.
     *
     * <p>로그인 전에는 토큰이 없어 secret-id 를 조회할 수 없다. 로그인 자체가 검증이 되고,
     * 로그인 후 받은 토큰으로 secret-id 를 조회해 만료 시각을 기록해 둔다.
     */
    public void initialize() {
        loginLock.lock();
        try {
            VaultToken t = authenticator.login(roleId, credential.get().secretId());
            swapToken(t);
            log.info("최초 AppRole 로그인 성공: {}", t);
            recordExpirationQuietly(t);
        } finally {
            loginLock.unlock();
        }
    }

    /**
     * 사용할 토큰을 반환한다. (API/워커 스레드가 매 요청마다 호출)
     *
     * <p>대부분은 락 없이 바로 반환한다. 만료가 임박했을 때만 재로그인한다.
     * (정상이라면 TokenRenewer 가 미리 연장하므로 이 경로는 안전장치 역할)
     */
    public VaultToken getToken() {
        VaultToken t = token.get();
        if (t == null || t.isExpiringWithin(EXPIRY_MARGIN)) {
            return refreshIfStale(t);
        }
        return t;
    }

    /** 현재 토큰을 그대로 반환한다. (갱신/상태 조회용, 재로그인하지 않음. 종료 후에는 null) */
    public VaultToken currentToken() {
        return token.get();
    }

    /**
     * 토큰으로 Vault 를 호출하고, 토큰이 무효(만료/폐기)여서 403 이 난 경우에만 재로그인 후 1회 재시도한다.
     *
     * <p>KV 조회/저장 등 토큰이 필요한 모든 호출이 이 메서드를 거친다.
     *
     * <p>Vault 는 "토큰이 무효"인 경우와 "정책상 권한이 없는" 경우 모두 403 을 준다.
     * 정책 권한 문제인데 매번 재로그인하면 (예: API 로 허용되지 않은 경로에 반복 저장 요청) 토큰이 계속 새로 발급되므로,
     * 403 을 받으면 lookup-self 로 토큰이 아직 유효한지 먼저 확인한다.
     */
    public <T> T executeWithToken(TokenCall<T> call) {
        VaultToken t = getToken();
        try {
            return call.apply(t);
        } catch (VaultException e) {
            if (!e.isPermissionDenied()) {
                throw e;
            }
            if (isTokenValid(t)) {
                // 토큰은 멀쩡함 -> 정책 권한 부족. 재로그인해도 결과가 같으므로 그대로 던진다.
                throw e;
            }
            log.warn("토큰 무효(403) -> 재로그인 후 재시도 (token={})", t.accessor());
            VaultToken fresh = refreshIfStale(t);
            return call.apply(fresh);
        }
    }

    /**
     * stale 토큰이 아직 현재 토큰일 때만 재로그인한다. (장애 복구용)
     *
     * <p>여러 스레드가 같은 토큰으로 403 을 받고 동시에 이 메서드를 호출하면,
     * 첫 번째 스레드만 실제 로그인하고 나머지는 락을 얻은 뒤 "이미 교체됨"을 확인하고 새 토큰을 그대로 가져간다.
     * (renew 로 인스턴스만 바뀐 경우를 구분하기 위해 인스턴스가 아니라 토큰 값으로 비교한다)
     *
     * <p>토큰이 죽은 상태라 이 시점에는 secret-id 를 조회할 수 없다. 대신 미리 조회해 둔 만료 시각으로 판단해서,
     * secret-id 가 유효할 때만 로그인한다. 로그인할 수 없으면 Vault 를 호출하지 않고 {@link LoginUnavailableException} 을 던진다.
     */
    public VaultToken refreshIfStale(VaultToken stale) {
        loginLock.lock();
        try {
            VaultToken current = token.get();
            if (current != null && !current.sameTokenAs(stale) && !current.isExpiringWithin(EXPIRY_MARGIN)) {
                return current; // 다른 스레드가 이미 재로그인함
            }
            checkLoginAllowed();
            SecretIdCredential cred = credential.get();
            try {
                VaultToken fresh = login(cred);
                swapToken(fresh);
                log.info("재로그인 완료: {}", fresh);
                recordExpirationQuietly(fresh);
                return fresh;
            } catch (VaultException e) {
                if (e.statusCode() == 400) {
                    // secret-id 만료/폐기/잘못됨. 같은 secret-id 로 다시 시도해 봐야 실패 횟수만 쌓인다.
                    credentialRejected = true;
                    log.error("현재 secret-id 가 Vault 에서 거부되었습니다(만료/폐기/잘못됨). "
                            + "새 secret-id 가 전달될 때까지 로그인을 시도하지 않습니다.");
                    throw new LoginUnavailableException("secret-id 가 만료되었거나 유효하지 않습니다. "
                            + "오케스트레이터가 새 secret-id 를 전달해야 합니다.", e);
                }
                if (e.statusCode() == 403) {
                    throw new LoginUnavailableException(lockoutMessage(), e);
                }
                throw e;
            }
        } finally {
            loginLock.unlock();
        }
    }

    /**
     * renew-self 결과를 반영한다. (TokenRenewer 가 호출)
     *
     * @param before  갱신 요청에 사용한 토큰 (currentToken() 으로 읽은 인스턴스)
     * @param renewed TTL 이 연장된 토큰
     * @return 반영 성공 여부. 그 사이 재로그인으로 토큰이 바뀌었다면 false
     */
    public boolean applyRenewal(VaultToken before, VaultToken renewed) {
        return token.compareAndSet(before, renewed);
    }

    /**
     * 전달받은 새 secret-id 로 교체한다. (SecretIdFileWatcher 가 호출)
     *
     * <p><b>토큰이 살아 있으면 (평소)</b>: 로그인하지 않는다. secret-id/lookup 으로 확인해서
     * Vault 에 존재하고 만료 전일 때만 보관한다. 토큰은 그대로 유지된다.
     * 확인에 실패하면 예외를 던지고 기존 secret-id 를 유지한다. (잘못된 파일이 전달되어도 앱이 망가지지 않음)
     *
     * <p><b>토큰이 죽어 있으면 (장애 중)</b>: 조회할 토큰이 없으므로, 새 secret-id 로 로그인하는 것으로 검증과 복구를 함께 한다.
     * 로그인이 잠겨 있으면 검증할 수 없으므로, 신뢰된 오케스트레이터가 보낸 secret-id 를 일단 보관해 두고
     * 잠금 대기가 끝난 뒤 재로그인할 때 사용한다.
     */
    public void switchSecretId(SecretIdCredential newCredential) {
        loginLock.lock();
        try {
            VaultToken t = token.get();
            if (t != null && !t.isExpiringWithin(EXPIRY_MARGIN)) {
                try {
                    adoptVerified(verify(newCredential, t));
                    return;
                } catch (VaultException e) {
                    if (!e.isPermissionDenied() || isTokenValid(t)) {
                        // 토큰은 유효한데 403 → 앱 정책에 secret-id/lookup 권한이 없음 (설정 문제)
                        throw e.isPermissionDenied()
                                ? new VaultException(403, "secret-id 조회 권한이 없습니다. 앱 정책에 "
                                        + "auth/approle/role/<role>/secret-id/lookup (update) 을 추가하세요.")
                                : e;
                    }
                    log.warn("토큰이 무효라 secret-id 를 조회할 수 없어 로그인으로 검증합니다.");
                }
            }
            switchByLogin(newCredential);
        } finally {
            loginLock.unlock();
        }
    }

    public SecretIdCredential currentCredential() {
        return credential.get();
    }

    /**
     * 로그인할 수 없는 사유. 로그인 가능하면 null. (/health 표시용)
     * <ul>
     *   <li>SECRET_ID_EXPIRED  : 현재 secret-id 의 만료 시각이 지남 → 새 secret-id 전달 필요</li>
     *   <li>SECRET_ID_REJECTED : 현재 secret-id 가 Vault 에서 거부됨 → 새 secret-id 전달 필요</li>
     *   <li>LOGIN_LOCKED_OUT   : 로그인 잠김 의심 → loginBlockedUntil 까지 대기</li>
     * </ul>
     * 평소에는 로그인하지 않으므로 값이 있어도 당장 장애는 아니다. 다만 토큰을 잃으면 복구할 수 없는 상태라는 뜻이다.
     */
    public String loginBlockedReason() {
        if (credentialRejected) {
            return "SECRET_ID_REJECTED";
        }
        if (credential.get().isExpired()) {
            return "SECRET_ID_EXPIRED";
        }
        if (Instant.now().isBefore(loginBlockedUntil)) {
            return "LOGIN_LOCKED_OUT (until " + loginBlockedUntil + ")";
        }
        return null;
    }

    /** 앱 종료 시 현재 토큰을 즉시 폐기한다. */
    public void revokeCurrent() {
        VaultToken t = token.getAndSet(null);
        if (t != null) {
            revokeQuietly(t);
        }
    }

    /**
     * secret-id/lookup 으로 확인한다. 존재하지 않거나 만료 시각이 지났으면 예외. (loginLock 안에서만 호출)
     *
     * @return 만료 시각 등이 채워진 secret-id
     */
    private SecretIdCredential verify(SecretIdCredential cred, VaultToken t) {
        Optional<SecretIdCredential> found = secretIdLookup.lookup(cred, t.token());
        if (found.isEmpty()) {
            throw new IllegalStateException("전달된 secret-id 가 Vault 에 없습니다(잘못된 값 / 폐기됨 / 사용 횟수 소진). 기존 secret-id 를 유지합니다.");
        }
        if (found.get().isExpired()) {
            throw new IllegalStateException("전달된 secret-id 가 이미 만료되었습니다(expiresAt=" + found.get().expiresAt()
                    + "). 기존 secret-id 를 유지합니다.");
        }
        return found.get();
    }

    /** 확인된 secret-id 를 보관한다. 토큰은 바꾸지 않는다. (loginLock 안에서만 호출) */
    private void adoptVerified(SecretIdCredential verified) {
        credential.set(verified);
        credentialRejected = false;
        log.info("새 secret-id 확인(lookup) 및 교체 완료 — 토큰은 유지: {}", verified);
    }

    /** 토큰이 없을 때: 새 secret-id 로 로그인해서 검증 + 복구한다. (loginLock 안에서만 호출) */
    private void switchByLogin(SecretIdCredential newCredential) {
        if (Instant.now().isBefore(loginBlockedUntil)) {
            adoptUnverified(newCredential);
            return;
        }
        try {
            VaultToken fresh = login(newCredential);
            credential.set(newCredential);
            credentialRejected = false;
            swapToken(fresh);
            log.info("새 secret-id 로 로그인(복구) 완료: {} / {}", newCredential, fresh);
            recordExpirationQuietly(fresh);
        } catch (VaultException e) {
            if (e.statusCode() == 403) {
                adoptUnverified(newCredential);
                return;
            }
            throw e; // 400 등: 잘못된 secret-id → 기존 secret-id 유지
        }
    }

    /**
     * 현재 secret-id 가 아직 조회되지 않았으면 조회해서 만료 시각을 기록한다. (loginLock 안에서만 호출)
     * 실패해도 로그인/토큰에는 영향이 없으므로 경고만 남긴다.
     */
    private void recordExpirationQuietly(VaultToken t) {
        SecretIdCredential cred = credential.get();
        if (cred.verified()) {
            return;
        }
        try {
            secretIdLookup.lookup(cred, t.token()).ifPresent(found -> {
                credential.set(found);
                log.info("secret-id 만료 시각 확인: {}", found);
            });
        } catch (VaultException e) {
            log.warn("secret-id 조회 실패(만료 시각을 모름): {}", e.getMessage());
        }
    }

    /**
     * AppRole 로그인. 403 이 나면 lockout 으로 보고 일정 시간 로그인을 멈춘다. (loginLock 안에서만 호출)
     *
     * <p>AppRole 로그인은 토큰 없이 호출하므로, 403 은 사실상 user lockout 으로 role-id 가 잠긴 경우다.
     * (secret-id 가 틀린 경우는 400 "invalid role or secret ID")
     */
    private VaultToken login(SecretIdCredential cred) {
        try {
            VaultToken t = authenticator.login(roleId, cred.secretId());
            loginBlockedUntil = Instant.EPOCH;
            return t;
        } catch (VaultException e) {
            if (e.statusCode() == 403) {
                loginBlockedUntil = Instant.now().plus(LOCKOUT_BACKOFF);
                log.error(lockoutMessage());
            }
            throw e;
        }
    }

    /**
     * 로그인해도 되는 상태인지 확인한다. 안 되면 Vault 를 호출하지 않고 바로 예외를 던진다. (loginLock 안에서만 호출)
     * <p>"secret-id 가 유효할 때만 로그인" 하기 위한 관문이다.
     */
    private void checkLoginAllowed() {
        if (credentialRejected) {
            throw new LoginUnavailableException("현재 secret-id 가 Vault 에서 거부되어 새 secret-id 전달을 기다리는 중입니다.");
        }
        SecretIdCredential cred = credential.get();
        if (cred.isExpired()) {
            credentialRejected = true;
            log.error("현재 secret-id 가 만료되었습니다(expiresAt={}). 새 secret-id 가 전달될 때까지 로그인을 시도하지 않습니다.",
                    cred.expiresAt());
            throw new LoginUnavailableException("secret-id 가 만료되었습니다(expiresAt=" + cred.expiresAt()
                    + "). 오케스트레이터가 새 secret-id 를 전달해야 합니다.");
        }
        Instant until = loginBlockedUntil;
        if (Instant.now().isBefore(until)) {
            throw new LoginUnavailableException("로그인 잠김(lockout) 의심으로 " + until + " 까지 로그인을 멈춘 상태입니다.");
        }
    }

    /** lockout 으로 검증할 수 없는 새 secret-id 를 일단 보관한다. (loginLock 안에서만 호출) */
    private void adoptUnverified(SecretIdCredential newCredential) {
        credential.set(newCredential);
        credentialRejected = false;
        log.warn("로그인 잠김 대기 중이라 새 secret-id 를 검증 없이 보관합니다: {} ({} 이후 재로그인 시 사용)",
                newCredential, loginBlockedUntil);
    }

    private String lockoutMessage() {
        return "AppRole 로그인 403: role-id 가 잠겼을 가능성이 큽니다(로그인 연속 실패 → Vault user lockout). "
                + LOCKOUT_BACKOFF.toSeconds() + "초 동안 로그인을 멈춥니다. "
                + "확인: vault read sys/locked-users / 해제: vault write -f sys/locked-users/<mount_accessor>/unlock/<role_id>";
    }

    /** lookup-self 로 토큰이 아직 유효한지 확인한다. (default 정책에 포함된 권한) */
    private boolean isTokenValid(VaultToken t) {
        try {
            client.get("auth/token/lookup-self", t.token());
            return true;
        } catch (VaultException e) {
            return false;
        }
    }

    /** 토큰을 원자적으로 교체하고, 이전 토큰은 grace 시간 후 폐기하도록 예약한다. (loginLock 안에서만 호출) */
    private void swapToken(VaultToken fresh) {
        VaultToken old = token.getAndSet(fresh);
        if (old != null && !old.sameTokenAs(fresh)) {
            scheduler.schedule(() -> revokeQuietly(old), revokeGrace.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 토큰 자신으로 revoke-self 를 호출해 폐기한다. (default 정책에 포함된 권한이라 별도 정책이 필요 없음)
     * 이미 만료된 토큰이면 403 이 나는데, 어차피 쓸 수 없는 토큰이므로 무시한다.
     */
    private void revokeQuietly(VaultToken t) {
        try {
            client.post("auth/token/revoke-self", t.token(), null);
            log.info("이전 토큰 폐기(revoke-self): accessor={}", t.accessor());
        } catch (VaultException e) {
            log.debug("이전 토큰 폐기 실패(이미 만료되었을 수 있음): accessor={}, {}", t.accessor(), e.getMessage());
        }
    }

    /** 토큰을 받아 Vault 를 호출하는 함수형 인터페이스 */
    @FunctionalInterface
    public interface TokenCall<T> {
        T apply(VaultToken token);
    }
}
