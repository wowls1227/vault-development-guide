package com.example.vault.api;

import com.example.vault.auth.SecretIdCredential;
import com.example.vault.auth.TokenManager;
import com.example.vault.auth.VaultToken;
import com.example.vault.client.VaultException;
import com.example.vault.kv.KvSecret;
import com.example.vault.kv.KvSecretClient;
import com.example.vault.util.NamedThreadFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 외부에서 KV 를 읽고 저장할 수 있게 해주는 HTTP API 서버. (JDK 내장 HttpServer 사용)
 *
 * <p>앱은 기동 후 이 서버를 띄워 놓고 요청이 올 때까지 대기한다.
 * 요청은 고정 크기 스레드 풀(api-N)에서 동시에 처리되며, 모든 요청이 TokenManager 의 토큰 하나를 공유한다.
 *
 * <pre>
 *  GET  /v1/kv/{path}   KV 시크릿 조회           → 200 {"path":..,"version":..,"data":{..}}
 *  PUT  /v1/kv/{path}   KV 시크릿 저장(POST 동일) → 200 {"path":..,"version":..}
 *                       요청 바디: 저장할 key/value JSON 객체  예) {"username":"u","password":"p"}
 *  GET  /health         토큰/secret-id 상태 (갱신 동작 확인용, 시크릿 원문은 포함하지 않음)
 * </pre>
 *
 * <p>{path} 는 KV 마운트 아래 경로다. (예: /v1/kv/sample-app/config → secret/data/sample-app/config)
 * 어떤 경로를 읽고 쓸 수 있는지는 이 서버가 아니라 Vault 정책이 결정한다. 정책에 없는 경로면 403 을 그대로 돌려준다.
 *
 * <p>주의: 이 API 자체에는 인증이 없다. 기본값으로 127.0.0.1 에만 바인딩한다.
 */
public final class KvApiServer {

    private static final Logger log = LoggerFactory.getLogger(KvApiServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final String KV_PREFIX = "/v1/kv/";
    /** 경로 세그먼트에 허용하는 문자. 경로 조작(../, 다른 Vault API 로의 우회)을 막기 위해 제한한다. */
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_.\\-]+(/[A-Za-z0-9_.\\-]+)*");
    /** 요청 바디 최대 크기 (1MB) */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final KvSecretClient kv;
    private final TokenManager tokenManager;
    private final HttpServer server;
    private final ExecutorService executor;

    public KvApiServer(String bindAddr, int port, int threads, KvSecretClient kv, TokenManager tokenManager) throws IOException {
        this.kv = kv;
        this.tokenManager = tokenManager;
        this.server = HttpServer.create(new InetSocketAddress(bindAddr, port), 0);
        this.executor = Executors.newFixedThreadPool(threads, new NamedThreadFactory("api"));
        server.setExecutor(executor);
        server.createContext(KV_PREFIX, this::handleKv);
        server.createContext("/health", this::handleHealth);
    }

    public void start() {
        server.start();
        log.info("API 서버 시작: http://{}:{}", server.getAddress().getHostString(), server.getAddress().getPort());
    }

    /** 처리 중인 요청은 최대 2초까지 기다린 뒤 서버를 내린다. */
    public void stop() {
        server.stop(2);
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("API 서버 종료");
    }

    /** /v1/kv/{path} 처리: GET = 조회, PUT/POST = 저장 */
    private void handleKv(HttpExchange ex) throws IOException {
        long start = System.currentTimeMillis();
        int status = 500;
        try {
            String path = URLDecoder.decode(ex.getRequestURI().getRawPath().substring(KV_PREFIX.length()), StandardCharsets.UTF_8);
            if (!isSafePath(path)) {
                status = sendError(ex, 400, "잘못된 경로: " + path);
                return;
            }

            switch (ex.getRequestMethod()) {
                case "GET" -> {
                    KvSecret secret = kv.read(path);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("path", path);
                    body.put("version", secret.version());
                    body.put("data", secret.data());
                    status = sendJson(ex, 200, body);
                }
                case "PUT", "POST" -> {
                    Map<String, Object> data = readJsonObject(ex);
                    if (data == null || data.isEmpty()) {
                        status = sendError(ex, 400, "요청 바디는 비어 있지 않은 JSON 객체여야 합니다.");
                        return;
                    }
                    int version = kv.write(path, data);
                    status = sendJson(ex, 200, Map.of("path", path, "version", version));
                }
                default -> status = sendError(ex, 405, "지원하지 않는 메서드: " + ex.getRequestMethod());
            }
        } catch (BadRequestException e) {
            status = sendError(ex, 400, e.getMessage());
        } catch (VaultException e) {
            status = sendError(ex, toHttpStatus(e), e.getMessage());
        } catch (Exception e) {
            log.error("요청 처리 중 오류", e);
            status = sendError(ex, 500, "내부 오류");
        } finally {
            log.info("{} {} -> {} ({}ms)", ex.getRequestMethod(), ex.getRequestURI().getPath(), status,
                    System.currentTimeMillis() - start);
            ex.close();
        }
    }

    /**
     * /health 처리: 현재 토큰과 secret-id 상태를 보여준다. (토큰/secret-id 원문은 포함하지 않음)
     *
     * <ul>
     *   <li>status    : 토큰이 만료 전이면 UP(200), 없거나 만료되었으면 DOWN(503)</li>
     *   <li>loginBlocked : 재로그인할 수 없는 사유 (UP 이어도 값이 있으면, 토큰을 잃었을 때 복구할 수 없다는 뜻)</li>
     *   <li>secretId     : 다음 로그인에 쓸 secret-id 의 확인 여부 / 만료 시각</li>
     * </ul>
     * 만료 여부는 앱이 계산한 시각 기준이다. Vault 에서 강제 폐기된 경우는 다음 Vault 호출에서 감지된다.
     */
    private void handleHealth(HttpExchange ex) throws IOException {
        try {
            VaultToken t = tokenManager.currentToken();
            SecretIdCredential c = tokenManager.currentCredential();
            boolean up = t != null && t.remainingSec() > 0;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", up ? "UP" : "DOWN");
            String blocked = tokenManager.loginBlockedReason();
            if (blocked != null) {
                body.put("loginBlocked", blocked);
            }
            if (t != null) {
                body.put("token", Map.of(
                        "accessor", t.accessor(),
                        "expiresAt", t.expiresAt().toString(),
                        "remainingSec", t.remainingSec()));
            }
            Map<String, Object> sid = new LinkedHashMap<>();
            sid.put("accessor", c.accessor());
            sid.put("verified", c.verified());                       // secret-id/lookup 으로 확인했는지
            sid.put("expiresAt", c.expiresAt() == null ? null : c.expiresAt().toString());
            sid.put("remainingSec", c.remainingSec());               // -1 = 만료 시각을 모르거나 만료 없음
            sid.put("loadedAt", c.loadedAt().toString());
            body.put("secretId", sid);
            sendJson(ex, up ? 200 : 503, body);
        } finally {
            ex.close();
        }
    }

    /** 빈 경로, "." / ".." 세그먼트, 허용되지 않은 문자를 거부한다. */
    private static boolean isSafePath(String path) {
        if (!SAFE_PATH.matcher(path).matches()) {
            return false;
        }
        for (String seg : path.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** 요청 바디를 JSON 객체로 읽는다. 크기 초과 / JSON 객체가 아니면 BadRequestException */
    private static Map<String, Object> readJsonObject(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BadRequestException("요청 바디가 너무 큽니다 (최대 1MB)");
        }
        if (bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, MAP_TYPE);
        } catch (IOException e) {
            throw new BadRequestException("요청 바디는 JSON 객체여야 합니다.");
        }
    }

    /**
     * Vault 오류 코드를 API 응답 코드로 변환한다.
     * <ul>
     *   <li>503: 지금은 Vault 에 로그인할 수 없음 (LoginUnavailableException: secret-id 거부 / lockout 대기)</li>
     *   <li>400/403/404: Vault 응답 코드 그대로</li>
     *   <li>그 외(응답을 못 받음, Vault 5xx): 502 (게이트웨이 오류)</li>
     * </ul>
     */
    private static int toHttpStatus(VaultException e) {
        int s = e.statusCode();
        return (s == 400 || s == 403 || s == 404 || s == 503) ? s : 502;
    }

    private static int sendError(HttpExchange ex, int status, String message) throws IOException {
        return sendJson(ex, status, Map.of("error", message));
    }

    private static int sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        return status;
    }

    /** 클라이언트 요청 오류(400) */
    private static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }
}
