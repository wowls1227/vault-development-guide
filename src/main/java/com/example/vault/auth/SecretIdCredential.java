package com.example.vault.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * AppRole secret-id 한 건 (불변 객체).
 *
 * @param secretId  로그인에 사용하는 secret-id 값 (민감 정보)
 * @param accessor  secret-id accessor (로그/상태 표시용 식별자). 아직 조회하지 않았으면 null 일 수 있다.
 * @param loadedAt  앱이 이 secret-id 를 전달받은 시각
 * @param verified  secret-id/lookup 으로 Vault 에 존재하는 것을 확인했는지 여부
 * @param expiresAt 만료 시각 (lookup 결과의 expiration_time). verified=true 인데 null 이면 만료 없음,
 *                  verified=false 면 아직 모름
 */
public record SecretIdCredential(String secretId, String accessor, Instant loadedAt, boolean verified, Instant expiresAt) {

    /** 전달받은 secret-id. 아직 Vault 에서 확인하지 않은 상태 */
    public static SecretIdCredential unverified(String secretId) {
        return new SecretIdCredential(secretId, null, Instant.now(), false, null);
    }

    /** secret-id/lookup 결과로 확인된 secret-id */
    public SecretIdCredential withLookup(String accessor, Instant expiresAt) {
        return new SecretIdCredential(secretId, accessor, loadedAt, true, expiresAt);
    }

    /**
     * 만료 시각이 지났는지. 만료 시각을 모르면(미확인 / 만료 없음) false.
     *
     * <p>Vault 는 만료된 secret-id 를 즉시 지우지 않고 주기적으로 정리하기 때문에, 만료 직후 잠깐은 로그인이 성공할 수도 있다.
     * 앱은 expiration_time 을 기준으로 더 엄격하게 판단한다.
     */
    public boolean isExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    /** 만료까지 남은 시간(초). 만료 시각을 모르면 -1 */
    public long remainingSec() {
        return expiresAt == null ? -1 : Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
    }

    /** secret-id 원문이 로그에 찍히지 않도록 accessor 만 출력한다. */
    @Override
    public String toString() {
        return "SecretId{accessor=" + accessor + ", verified=" + verified + ", expiresAt=" + expiresAt + "}";
    }
}
