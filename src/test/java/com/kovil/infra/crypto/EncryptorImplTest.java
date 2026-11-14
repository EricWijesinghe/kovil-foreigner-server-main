package com.kovil.infra.crypto;

import com.kovil.infra.crypto.exception.CryptoErrorCode;
import com.kovil.infra.crypto.exception.CryptoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptorImplTest {

    private static final String PHONE = "01012345678";

    private EncryptorImpl encryptor;

    private static CryptoKeyProvider keyProvider(byte seed) {
        byte[] aes = new byte[32];
        byte[] hmac = new byte[32];
        for (int i = 0; i < 32; i++) {
            aes[i] = (byte) (i + seed);
            hmac[i] = (byte) (i - seed);
        }
        return new CryptoKeyProvider() {
            @Override
            public int activeVersion() {
                return 1;
            }

            @Override
            public byte[] aesKey(int version) {
                return aes.clone();
            }

            @Override
            public byte[] hmacKey(int version) {
                return hmac.clone();
            }
        };
    }

    @BeforeEach
    void setUp() {
        encryptor = new EncryptorImpl(keyProvider((byte) 1));
    }

    @Test
    @DisplayName("암호화한 값을 복호화하면 원문이 돌아온다")
    void round_trip() {
        assertThat(encryptor.decrypt(encryptor.encrypt(PHONE))).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("빈 문자열과 유니코드도 왕복한다")
    void round_trip_edge_inputs() {
        assertThat(encryptor.decrypt(encryptor.encrypt(""))).isEmpty();
        assertThat(encryptor.decrypt(encryptor.encrypt("김임차 🇰🇷 Nguyễn"))).isEqualTo("김임차 🇰🇷 Nguyễn");
    }

    @Test
    @DisplayName("암호문에 원문이 그대로 남지 않는다")
    void cipher_text_does_not_leak_plain_text() {
        assertThat(encryptor.encrypt(PHONE)).doesNotContain(PHONE);
    }

    // phone은 UNIQUE가 아니다. 같은 번호가 매번 같은 암호문이 되면 암호문만 보고
    // 동일인을 묶을 수 있으므로, IV는 매 호출 달라야 한다.
    @Test
    @DisplayName("같은 평문을 여러 번 암호화하면 매번 다른 암호문이 나온다")
    void same_plain_text_yields_different_cipher_text() {
        Set<String> cipherTexts = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            cipherTexts.add(encryptor.encrypt(PHONE));
        }

        assertThat(cipherTexts).hasSize(50);
        cipherTexts.forEach(c -> assertThat(encryptor.decrypt(c)).isEqualTo(PHONE));
    }

    @Test
    @DisplayName("다른 키로 암호화한 값은 복호화되지 않는다")
    void cipher_text_from_another_key_is_rejected() {
        String fromOtherKey = new EncryptorImpl(keyProvider((byte) 99)).encrypt(PHONE);

        assertThatThrownBy(() -> encryptor.decrypt(fromOtherKey))
                .isInstanceOf(CryptoException.class)
                .extracting(e -> ((CryptoException) e).getErrorCode())
                .isEqualTo(CryptoErrorCode.AES_DECRYPT_FAILED);
    }

    // GCM 인증 태그가 하는 일이 이것이다. 조용히 다른 평문을 뱉으면 안 된다.
    @Test
    @DisplayName("암호문이 한 바이트라도 변조되면 복호화가 실패한다")
    void tampered_cipher_text_is_rejected() {
        // 암호문은 "v{버전}:Base64" 다. 접두사는 보존한 채 본문만 건드려야
        // "버전은 멀쩡한데 내용이 바뀐" 상황을 검사한다.
        String encrypted = encryptor.encrypt(PHONE);
        String prefix = encrypted.substring(0, encrypted.indexOf(':') + 1);
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(prefix.length()));
        raw[raw.length - 1] ^= 0x01;
        String tampered = prefix + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(CryptoException.class)
                .extracting(e -> ((CryptoException) e).getErrorCode())
                .isEqualTo(CryptoErrorCode.AES_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("IV보다 짧거나 Base64가 아닌 입력은 예외로 떨어진다")
    void malformed_cipher_text_is_rejected() {
        assertThatThrownBy(() -> encryptor.decrypt("not base64!!"))
                .isInstanceOf(CryptoException.class);

        assertThatThrownBy(() -> encryptor.decrypt(Base64.getEncoder().encodeToString(new byte[8])))
                .isInstanceOf(CryptoException.class);

        assertThatThrownBy(() -> encryptor.decrypt(""))
                .isInstanceOf(CryptoException.class);
    }
}
