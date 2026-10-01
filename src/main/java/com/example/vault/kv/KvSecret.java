package com.example.vault.kv;

import java.util.Map;

/**
 * KV v2 에서 읽은 시크릿 한 건.
 *
 * @param data    시크릿 key/value (값은 문자열 외에 숫자/객체 등 JSON 값도 가능)
 * @param version KV v2 버전 번호 (저장할 때마다 1씩 증가)
 */
public record KvSecret(Map<String, Object> data, int version) {
}
