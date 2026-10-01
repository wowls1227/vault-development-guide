package com.example.vault.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * AppRole 로그인으로 받은 Vault 클라이언트 토큰 (불변 객체).
 *
 * <p>불변이므로 여러 스레드가 동시에 읽어도 안전하다. 토큰이 바뀌거나 갱신(renew)되면 새 인스턴스로 통째로 교체한다.
 *
 * @param token            X-Vault-Token 헤더에 넣을 실제 토큰 값
 * @param accessor         토큰 accessor (토큰 값 대신 로그에 남기기 위한 식별자)
 * @param leaseDurationSec 토큰 TTL(초). issuedAt 기준
 * @param renewable        renew-self 로 TTL 을 연장할 수 있는지 여부
 * @param issuedAt         발급(또는 마지막 갱신) 시각 (만료 시각 계산용)
 */
public record VaultToken(String token, String accessor, long leaseDurationSec, boolean renewable, Instant issuedAt) {

    public Instant expiresAt() {
        return issuedAt.plusSeconds(leaseDurationSec);
    }

    /** 만료까지 남은 시간(초) */
    public long remainingSec() {
        return Math.max(0, Duration.between(Instant.now(), expiresAt()).toSeconds());
    }

    /** 지금부터 margin 이내에 만료되는지 여부. 만료 직전 토큰으로 호출하다 실패하는 것을 막기 위해 사용한다. */
    public boolean isExpiringWithin(Duration margin) {
        // leaseDuration 0 = 만료 없음(root 토큰 등)
        return leaseDurationSec > 0 && Instant.now().plus(margin).isAfter(expiresAt());
    }

    /** renew-self 로 TTL 이 연장된 같은 토큰. (토큰 값은 그대로, TTL 과 기준 시각만 바뀜) */
    public VaultToken withRenewedLease(long newLeaseDurationSec) {
        return new VaultToken(token, accessor, newLeaseDurationSec, renewable, Instant.now());
    }

    /** 같은 토큰 값인지 비교 (renew 로 인스턴스가 바뀌어도 같은 토큰이면 true) */
    public boolean sameTokenAs(VaultToken other) {
        return other != null && token.equals(other.token);
    }

    /** 토큰 원문이 로그에 찍히지 않도록 accessor 만 출력한다. */
    @Override
    public String toString() {
        return "VaultToken{accessor=" + accessor + ", ttl=" + leaseDurationSec + "s, expiresAt=" + expiresAt() + "}";
    }
}
