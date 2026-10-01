package com.example.vault.auth;

import com.example.vault.client.VaultHttpClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * secret-id 조회(lookup) 담당. 로그인하지 않고 secret-id 가 유효한지, 언제 만료되는지 확인한다.
 *
 * <p>{@code POST auth/{mount}/role/{role}/secret-id/lookup} 을 앱 토큰으로 호출한다.
 * 앱 정책에 이 경로의 update 권한이 필요하다. 이 권한으로는 secret-id 를 만들 수 없고,
 * 이미 가지고 있는 secret-id 의 정보만 볼 수 있다.
 *
 * <p>응답
 * <ul>
 *   <li>200: Vault 에 존재함. data.expiration_time 으로 만료 시각을 알 수 있다.
 *       (만료 시각이 지났어도 Vault 가 아직 정리하지 않았으면 200 이 올 수 있으므로 호출한 쪽에서 만료 여부를 따로 비교한다)</li>
 *   <li>204: 존재하지 않음 (잘못된 값 / 폐기됨 / 사용 횟수 소진 / 만료 후 정리됨)</li>
 * </ul>
 */
public final class SecretIdLookup {

    /** Vault 는 "만료 없음"(secret_id_ttl=0)을 expiration_time "0001-01-01T00:00:00Z" 로 표현한다. */
    private static final String NO_EXPIRATION_PREFIX = "0001-";

    private final VaultHttpClient client;
    private final String lookupPath;

    public SecretIdLookup(VaultHttpClient client, String appRoleMount, String roleName) {
        this.client = client;
        this.lookupPath = "auth/" + appRoleMount + "/role/" + roleName + "/secret-id/lookup";
    }

    /**
     * secret-id 를 조회한다.
     *
     * @param cred  조회할 secret-id
     * @param token 앱 토큰 (유효해야 함)
     * @return Vault 에 존재하면 accessor / 만료 시각을 채운 secret-id, 존재하지 않으면 empty
     * @throws com.example.vault.client.VaultException 403(토큰 무효 또는 정책에 lookup 권한 없음) 등
     */
    public Optional<SecretIdCredential> lookup(SecretIdCredential cred, String token) {
        JsonNode data = client.post(lookupPath, token, Map.of("secret_id", cred.secretId())).path("data");
        if (data.isMissingNode() || data.isNull()) {
            return Optional.empty(); // 204: 존재하지 않음
        }
        String exp = data.path("expiration_time").asText("");
        Instant expiresAt = (exp.isEmpty() || exp.startsWith(NO_EXPIRATION_PREFIX)) ? null : Instant.parse(exp);
        return Optional.of(cred.withLookup(data.path("secret_id_accessor").asText(null), expiresAt));
    }
}
