package com.example.vault.client;

/**
 * 지금은 Vault 에 로그인할 수 없는 상태를 나타내는 예외. (HTTP 503 으로 취급)
 *
 * <ul>
 *   <li>현재 secret-id 가 Vault 에서 거부됨(만료/폐기/잘못됨) → 오케스트레이터가 새 secret-id 를 전달해야 함</li>
 *   <li>로그인 잠김(user lockout) 의심 → 잠시 로그인 시도를 멈춘 상태</li>
 * </ul>
 *
 * <p>이 예외는 Vault 를 호출하지 않고 즉시 던질 수 있다.
 * 실패할 것이 뻔한 로그인을 반복하면 Vault 의 실패 횟수가 쌓여 role-id 가 잠기기 때문이다.
 */
public class LoginUnavailableException extends VaultException {

    public LoginUnavailableException(String message) {
        super(503, message);
    }

    public LoginUnavailableException(String message, VaultException cause) {
        super(503, message + " (원인: " + cause.getMessage() + ")");
        initCause(cause);
    }
}
