package com.example.vault.auth;

import com.example.vault.client.VaultHttpClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/**
 * AppRole auth method 로그인 담당.
 *
 * <p>role-id + secret-id 를 {@code POST /v1/auth/{mount}/login} 으로 보내고 클라이언트 토큰을 받는다.
 * 로그인 API 는 토큰 없이 호출한다. 이 클래스는 상태가 없어서 여러 스레드에서 동시에 써도 된다.
 */
public final class AppRoleAuthenticator {

    private final VaultHttpClient client;
    private final String loginPath;

    public AppRoleAuthenticator(VaultHttpClient client, String appRoleMount) {
        this.client = client;
        this.loginPath = "auth/" + appRoleMount + "/login";
    }

    /**
     * AppRole 로그인.
     *
     * <p>응답 예:
     * <pre>{"auth":{"client_token":"hvs...","accessor":"...","lease_duration":60,"renewable":true,...}}</pre>
     */
    public VaultToken login(String roleId, String secretId) {
        JsonNode res = client.post(loginPath, null, Map.of("role_id", roleId, "secret_id", secretId));
        JsonNode auth = res.path("auth");
        return new VaultToken(
                auth.path("client_token").asText(),
                auth.path("accessor").asText(),
                auth.path("lease_duration").asLong(),
                auth.path("renewable").asBoolean(),
                Instant.now());
    }
}
