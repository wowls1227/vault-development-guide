package com.example.vault.kv;

import com.example.vault.auth.TokenManager;
import com.example.vault.client.VaultHttpClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * KV v2 시크릿 조회/저장 담당.
 *
 * <p>KV v2 의 실제 API 경로는 {@code {mount}/data/{path}} 이다.
 * (CLI 의 "vault kv get secret/foo" 는 내부적으로 GET /v1/secret/data/foo 를 호출)
 *
 * <p>토큰은 매 호출마다 TokenManager 에서 받아 쓴다. 토큰 갱신/교체 중에도 여러 스레드(API 요청, 워커)가
 * 이 클래스를 동시에 호출해도 된다.
 */
public final class KvSecretClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final VaultHttpClient client;
    private final TokenManager tokenManager;
    private final String mount;

    public KvSecretClient(VaultHttpClient client, TokenManager tokenManager, String mount) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.mount = mount;
    }

    /**
     * 시크릿 최신 버전을 읽는다. 시크릿이 없으면 VaultException(404).
     * <p>응답 예: {"data":{"data":{"username":"app_user",...},"metadata":{"version":1,...}}}
     */
    public KvSecret read(String path) {
        JsonNode res = tokenManager.executeWithToken(t -> client.get(mount + "/data/" + path, t.token()));
        JsonNode data = res.path("data");
        return new KvSecret(MAPPER.convertValue(data.path("data"), MAP_TYPE), data.path("metadata").path("version").asInt());
    }

    /**
     * 시크릿을 저장한다. 경로가 없으면 새로 만들고, 있으면 새 버전으로 덮어쓴다. (KV v2 는 이전 버전을 보관)
     * <p>요청: POST {mount}/data/{path} {"data":{...}}
     * <p>응답 예: {"data":{"version":2,"created_time":"...",...}}
     *
     * @return 저장된 버전 번호
     */
    public int write(String path, Map<String, Object> data) {
        JsonNode res = tokenManager.executeWithToken(t ->
                client.post(mount + "/data/" + path, t.token(), Map.of("data", data)));
        return res.path("data").path("version").asInt();
    }
}
