package com.example.vault.client;

/**
 * Vault API 호출 실패를 표현하는 예외.
 *
 * <p>HTTP 상태 코드를 함께 보관해서, 호출하는 쪽이 403(토큰 만료/폐기/권한 없음)인지 판단하고
 * 재로그인 후 재시도할 수 있도록 한다.
 */
public class VaultException extends RuntimeException {

    /** HTTP 상태 코드. 네트워크 오류 등 응답을 받지 못한 경우 -1 */
    private final int statusCode;

    public VaultException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int statusCode() {
        return statusCode;
    }

    /** 403 = 토큰이 만료/폐기되었거나 정책상 권한이 없는 경우 (Vault 는 잘못된 토큰에도 403 을 준다) */
    public boolean isPermissionDenied() {
        return statusCode == 403;
    }
}
