package com.kovil.infra.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvCryptoKeyProviderTest {

    private static final String AES_32 = base64Of(32);
    private static final String HMAC_32 = base64Of(32);

    private static String base64Of(int bytes) {
        byte[] key = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            key[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(key);
    }

    private static EnvCryptoKeyProvider provider(String aesKey, String hmacKey) {
        return new EnvCryptoKeyProvider(new CryptoProperties("env", aesKey, hmacKey, null, null, null));
    }

    @Test
    @DisplayName("32바이트 키 두 개가 주어지면 정상 생성된다")
    void valid_keys_construct() {
        assertThatCode(() -> provider(AES_32, HMAC_32)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("키가 없으면 부팅을 멈춘다 — 어느 키가 비었는지 메시지에 담는다")
    void missing_key_fails_fast() {
        assertThatThrownBy(() -> provider(null, HMAC_32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.aes-key");

        assertThatThrownBy(() -> provider("", HMAC_32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.aes-key");

        assertThatThrownBy(() -> provider(AES_32, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.hmac-key");
    }

    @Test
    @DisplayName("Base64가 아니면 부팅을 멈춘다")
    void malformed_base64_fails_fast() {
        assertThatThrownBy(() -> provider("this is not base64!!", HMAC_32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    @DisplayName("AES 키는 정확히 32바이트여야 한다 — 길어도 짧아도 거부된다")
    void aes_key_must_be_exactly_256_bit() {
        assertThatThrownBy(() -> provider(base64Of(16), HMAC_32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.aes-key");

        assertThatThrownBy(() -> provider(base64Of(64), HMAC_32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.aes-key");
    }

    @Test
    @DisplayName("HMAC 키는 32바이트 이상이면 된다 — 더 길어도 허용된다")
    void hmac_key_has_only_a_lower_bound() {
        assertThatThrownBy(() -> provider(AES_32, base64Of(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.hmac-key");

        assertThatCode(() -> provider(AES_32, base64Of(64))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("반환한 배열을 호출자가 변조해도 내부 키는 그대로다")
    void returned_key_is_a_defensive_copy() {
        EnvCryptoKeyProvider provider = provider(AES_32, HMAC_32);

        byte[] taken = provider.getAesKey();
        taken[0] = (byte) 0xFF;

        assertThat(provider.getAesKey()).isNotEqualTo(taken);
        assertThat(provider.getAesKey()[0]).isEqualTo((byte) 0);
    }
}
