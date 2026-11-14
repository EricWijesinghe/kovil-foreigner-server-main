package com.kovil.infra.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HasherImplTest {

    private static final String PHONE = "01012345678";

    private HasherImpl hasher;

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
        hasher = new HasherImpl(keyProvider((byte) 1));
    }

    // users.phone_hash에 UNIQUE가 걸려 있다(1인 1계정 판정 축). 해시가 호출마다 달라지면
    // 같은 번호로 몇 개든 가입된다 — 이 프로젝트에서 salt를 쓰면 안 되는 이유다.
    @Test
    @DisplayName("같은 번호는 항상 같은 해시가 된다")
    void hashing_is_deterministic() {
        String first = hasher.hash(PHONE);
        for (int i = 0; i < 20; i++) {
            assertThat(hasher.hash(PHONE)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("새 인스턴스여도 같은 키면 같은 해시가 나온다 — 재배포가 기존 계정을 끊지 않는다")
    void hashing_survives_restart() {
        assertThat(new HasherImpl(keyProvider((byte) 1)).hash(PHONE)).isEqualTo(hasher.hash(PHONE));
    }

    @Test
    @DisplayName("한 자리만 달라도 다른 해시가 된다")
    void different_input_yields_different_hash() {
        assertThat(hasher.hash("01012345678")).isNotEqualTo(hasher.hash("01012345679"));
    }

    @Test
    @DisplayName("키가 다르면 같은 번호도 다른 해시가 된다 — 키 교체는 대조를 끊는다")
    void different_key_yields_different_hash() {
        assertThat(new HasherImpl(keyProvider((byte) 99)).hash(PHONE)).isNotEqualTo(hasher.hash(PHONE));
    }

    @Test
    @DisplayName("해시는 원문을 담지 않고 SHA-256 길이(32바이트)의 Base64다")
    void hash_is_base64_of_256_bit() {
        String hash = hasher.hash(PHONE);

        assertThat(hash).doesNotContain(PHONE);
        assertThat(Base64.getDecoder().decode(hash)).hasSize(32);
        assertThat(hash.length()).isLessThanOrEqualTo(255);
    }

    @Test
    @DisplayName("빈 문자열과 유니코드도 해싱된다")
    void hashes_edge_inputs() {
        assertThat(hasher.hash("")).isNotBlank();
        assertThat(hasher.hash("김임차 🇰🇷")).isNotBlank();
    }
}
