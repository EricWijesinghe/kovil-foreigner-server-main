package com.kovil.infra.crypto;

import com.kovil.infra.crypto.exception.CryptoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 키 유출 대응(= 교체)이 실제로 가능한지를 고정한다.
// 교체 절차: 새 키를 active 로 올리고 옛 키를 previous 에 남긴다 → 새 데이터는 새 키로 쓰이고
// 옛 데이터는 계속 읽힌다 → 백필이 끝나면 previous 를 비운다.
class CryptoKeyRotationTest {

    private static final String PHONE = "01012345678";

    private static String base64Key(int seed) {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (i + seed);
        }
        return Base64.getEncoder().encodeToString(key);
    }

    private static final String KEY_V1 = base64Key(1);
    private static final String KEY_V2 = base64Key(99);

    private static EnvCryptoKeyProvider provider(int activeVersion, String aes, String hmac,
                                                 CryptoProperties.Previous previous) {
        return new EnvCryptoKeyProvider(
                new CryptoProperties("env", aes, hmac, activeVersion, previous, null));
    }

    private static EnvCryptoKeyProvider v1Only() {
        return provider(1, KEY_V1, KEY_V1, null);
    }

    // v1 으로 쓰던 서버가 v2 로 갈아탄 상태
    private static EnvCryptoKeyProvider rotatedToV2() {
        return provider(2, KEY_V2, KEY_V2, new CryptoProperties.Previous(1, KEY_V1, KEY_V1));
    }

    @Nested
    @DisplayName("암호문 버전 접두사")
    class VersionPrefix {

        @Test
        @DisplayName("새 암호문에는 현재 버전이 박힌다")
        void stamps_active_version() {
            assertThat(new EncryptorImpl(v1Only()).encrypt(PHONE)).startsWith("v1:");
            assertThat(new EncryptorImpl(rotatedToV2()).encrypt(PHONE)).startsWith("v2:");
        }

        // 이 형식을 도입하기 전에 저장된 값이 있다. 접두사가 없다고 못 읽으면 그 데이터가 죽는다.
        @Test
        @DisplayName("접두사가 없는 옛 암호문은 v1 으로 읽는다")
        void reads_legacy_ciphertext_as_v1() {
            EncryptorImpl v1 = new EncryptorImpl(v1Only());
            String legacy = v1.encrypt(PHONE).substring("v1:".length());

            assertThat(v1.decrypt(legacy)).isEqualTo(PHONE);
        }
    }

    @Nested
    @DisplayName("교체 중")
    class DuringRotation {

        @Test
        @DisplayName("옛 키로 만든 암호문을 새 키 서버가 그대로 읽는다 — 무중단 백필의 전제")
        void new_server_reads_old_ciphertext() {
            String writtenWithV1 = new EncryptorImpl(v1Only()).encrypt(PHONE);

            assertThat(new EncryptorImpl(rotatedToV2()).decrypt(writtenWithV1)).isEqualTo(PHONE);
        }

        @Test
        @DisplayName("백필은 읽고 다시 쓰는 것뿐이다 — 결과는 새 버전이 된다")
        void backfill_is_read_then_write() {
            EncryptorImpl rotated = new EncryptorImpl(rotatedToV2());
            String old = new EncryptorImpl(v1Only()).encrypt(PHONE);

            String migrated = rotated.encrypt(rotated.decrypt(old));

            assertThat(migrated).startsWith("v2:");
            assertThat(rotated.decrypt(migrated)).isEqualTo(PHONE);
        }

        @Test
        @DisplayName("HMAC 도 버전별로 다른 값을 낸다 — phone_hash 재계산이 필요한 이유")
        void hmac_differs_per_version() {
            String v1Hash = new HasherImpl(v1Only()).hash(PHONE);
            String v2Hash = new HasherImpl(rotatedToV2()).hash(PHONE);

            assertThat(v2Hash).isNotEqualTo(v1Hash);
        }
    }

    @Nested
    @DisplayName("백필 이후")
    class AfterBackfill {

        // previous 를 비우는 것이 교체의 마지막 단계다. 남겨두면 유출된 옛 키가 계속 유효하다.
        @Test
        @DisplayName("previous 를 비우면 옛 암호문은 더 이상 읽히지 않는다")
        void dropping_previous_key_closes_the_old_ciphertext() {
            String old = new EncryptorImpl(v1Only()).encrypt(PHONE);
            EncryptorImpl v2Only = new EncryptorImpl(provider(2, KEY_V2, KEY_V2, null));

            assertThatThrownBy(() -> v2Only.decrypt(old)).isInstanceOf(CryptoException.class);
        }

        @Test
        @DisplayName("모르는 버전을 만나면 현재 키로 시도하지 않고 멈춘다")
        void unknown_version_is_not_silently_retried() {
            assertThatThrownBy(() -> provider(2, KEY_V2, KEY_V2, null).aesKey(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version 1")
                    .hasMessageContaining("crypto.previous");
        }
    }

    @Nested
    @DisplayName("설정 오류는 부팅에서 잡는다")
    class Misconfiguration {

        @Test
        @DisplayName("previous 버전이 현재와 같으면 멈춘다")
        void previous_version_must_differ() {
            assertThatThrownBy(() -> provider(2, KEY_V2, KEY_V2,
                    new CryptoProperties.Previous(2, KEY_V1, KEY_V1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must differ");
        }

        @Test
        @DisplayName("previous 키만 있고 버전이 없으면 멈춘다")
        void previous_requires_version() {
            assertThatThrownBy(() -> provider(2, KEY_V2, KEY_V2,
                    new CryptoProperties.Previous(null, KEY_V1, KEY_V1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crypto.previous.version");
        }

        @Test
        @DisplayName("previous 키가 규격 미달이면 멈춘다")
        void previous_key_is_validated_too() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> provider(2, KEY_V2, KEY_V2,
                    new CryptoProperties.Previous(1, tooShort, KEY_V1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crypto.previous.aes-key");
        }

        @Test
        @DisplayName("active-version 이 0 이하면 멈춘다")
        void active_version_must_be_positive() {
            assertThatThrownBy(() -> provider(0, KEY_V1, KEY_V1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crypto.active-version");
        }

        @Test
        @DisplayName("버전을 지정하지 않으면 v1 이다 — 기존 배포가 그대로 뜬다")
        void defaults_to_version_one() {
            EnvCryptoKeyProvider legacy = new EnvCryptoKeyProvider(
                    new CryptoProperties("env", KEY_V1, KEY_V1, null, null, null));

            assertThat(legacy.activeVersion()).isEqualTo(1);
            assertThat(new EncryptorImpl(legacy).encrypt(PHONE)).startsWith("v1:");
        }
    }
}
