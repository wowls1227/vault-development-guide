package com.example.vault.client;

import com.example.vault.config.VaultConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Vault HTTP API 저수준 클라이언트.
 *
 * <p>역할:
 * <ul>
 *   <li>URL 조립 ({VAULT_ADDR}/v1/{path}), X-Vault-Token / X-Vault-Namespace 헤더 설정</li>
 *   <li>요청 바디 JSON 직렬화, 응답 JSON 파싱</li>
 *   <li>2xx 가 아닌 응답을 {@link VaultException} 으로 변환</li>
 * </ul>
 *
 * <p>JDK {@link HttpClient} 는 스레드 세이프하므로 인스턴스 하나를 모든 스레드(워커, 갱신 스케줄러)가 공유한다.
 * 이 클래스는 토큰을 보관하지 않는다. 어떤 토큰으로 호출할지는 매 호출마다 인자로 받는다.
 */
public final class VaultHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String namespace;

    public VaultHttpClient(VaultConfig config) {
        this.baseUrl = config.vaultAddr() + "/v1/";
        this.namespace = config.namespace();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** GET 요청. token 이 null 이면 토큰 헤더 없이 호출한다. */
    public JsonNode get(String path, String token) {
        return send(newRequest(path, token).GET(), path);
    }

    /** POST 요청. body 가 null 이면 빈 JSON 객체({})를 보낸다. */
    public JsonNode post(String path, String token, Map<String, ?> body) {
        String json;
        try {
            json = MAPPER.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException e) {
            throw new VaultException("요청 바디 직렬화 실패: " + path, e);
        }
        return send(newRequest(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)), path);
    }

    /** 공통 헤더를 채운 요청 빌더를 만든다. */
    private HttpRequest.Builder newRequest(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            b.header("X-Vault-Token", token);
        }
        if (namespace != null) {
            b.header("X-Vault-Namespace", namespace);
        }
        return b;
    }

    /** 요청을 전송하고 응답 상태 코드에 따라 JSON 반환 또는 예외를 던진다. */
    private JsonNode send(HttpRequest.Builder builder, String path) {
        HttpResponse<String> res;
        try {
            res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new VaultException("Vault 호출 실패(네트워크): " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("Vault 호출 중 인터럽트: " + path, e);
        }

        int status = res.statusCode();
        String body = res.body();

        // 204 No Content (예: revoke-self, secret-id destroy) 는 바디가 없다.
        if (status == 204 || body == null || body.isBlank()) {
            if (status >= 200 && status < 300) {
                return MissingNode.getInstance();
            }
            throw new VaultException(status, "Vault 오류 " + status + " : " + path);
        }

        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new VaultException("Vault 응답 파싱 실패: " + path, e);
        }

        if (status < 200 || status >= 300) {
            // Vault 오류 응답 형식: {"errors":["..."]}
            throw new VaultException(status, "Vault 오류 " + status + " : " + path + " " + json.path("errors"));
        }
        return json;
    }
}
