package com.kovil.infra.crypto;

import java.util.Base64;

// 키 규격 검증. env·kms 어느 경로로 들어오든 같은 문턱을 넘어야 한다 —
// 검증이 한 곳에 있어야 프로바이더를 늘려도 불변식이 갈라지지 않는다.
final class CryptoKeys {

    static final int AES_KEY_BYTES = 32;
    static final int HMAC_KEY_MIN_BYTES = 32;

    private CryptoKeys() {
    }

    // 키가 없거나 깨졌으면 여기서 부팅을 끝낸다. 통과시키면 첫 가입자의 phone_hash가
    // 잘못된 키로 계산되고, 그 시점엔 되돌릴 방법이 없다.
    static byte[] decodeBase64(String propertyName, String base64Value) {
        if (base64Value == null || base64Value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " is required but was not set");
        }
        try {
            return Base64.getDecoder().decode(base64Value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " is not valid Base64", e);
        }
    }

    static byte[] requireAesKey(String propertyName, byte[] key) {
        if (key.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                    propertyName + " must be 256-bit (32 bytes), but was " + key.length + " bytes");
        }
        return key;
    }

    static byte[] requireHmacKey(String propertyName, byte[] key) {
        if (key.length < HMAC_KEY_MIN_BYTES) {
            throw new IllegalArgumentException(
                    propertyName + " must be at least 256-bit (32 bytes), but was " + key.length + " bytes");
        }
        return key;
    }
}
