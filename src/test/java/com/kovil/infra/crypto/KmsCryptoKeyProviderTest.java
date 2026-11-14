package com.kovil.infra.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KmsCryptoKeyProviderTest {

    private static final String KEY_ID = "arn:aws:kms:ap-northeast-2:088146583376:key/abc-123";
    private static final String AES_CIPHERTEXT = Base64.getEncoder().encodeToString("aes-ct".getBytes());
    private static final String HMAC_CIPHERTEXT = Base64.getEncoder().encodeToString("hmac-ct".getBytes());

    private static CryptoProperties props(String keyId) {
        return new CryptoProperties("kms", AES_CIPHERTEXT, HMAC_CIPHERTEXT, null, null,
                keyId == null ? null : new CryptoProperties.Kms(keyId, null));
    }

    private static byte[] bytesOf(int length) {
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    private static KmsClient kmsReturning(byte[] aes, byte[] hmac) {
        KmsClient client = mock(KmsClient.class);
        when(client.decrypt(any(DecryptRequest.class))).thenAnswer(invocation -> {
            DecryptRequest request = invocation.getArgument(0);
            String ciphertext = new String(request.ciphertextBlob().asByteArray());
            byte[] plaintext = ciphertext.equals("aes-ct") ? aes : hmac;
            return DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(plaintext)).build();
        });
        return client;
    }

    @Test
    @DisplayName("부팅 때 키당 한 번만 복호화하고, 그 뒤로는 KMS를 부르지 않는다")
    void decrypts_once_per_key_at_boot() {
        KmsClient kms = kmsReturning(bytesOf(32), bytesOf(32));

        KmsCryptoKeyProvider provider = new KmsCryptoKeyProvider(kms, props(KEY_ID));
        provider.getAesKey();
        provider.getHmacKey();
        provider.getAesKey();

        // 핫패스(가입·로그인마다 호출되는 HMAC)에 네트워크 왕복이 붙으면 안 된다.
        verify(kms, times(2)).decrypt(any(DecryptRequest.class));
    }

    @Test
    @DisplayName("복호화 요청에 keyId를 실어 보낸다 — 암호문이 바꿔치기되어도 다른 키로는 안 풀린다")
    void passes_key_id_so_kms_verifies_the_ciphertext_origin() {
        KmsClient kms = kmsReturning(bytesOf(32), bytesOf(32));
        ArgumentCaptor<DecryptRequest> captor = ArgumentCaptor.forClass(DecryptRequest.class);

        new KmsCryptoKeyProvider(kms, props(KEY_ID));

        verify(kms, times(2)).decrypt(captor.capture());
        assertThat(captor.getAllValues()).extracting(DecryptRequest::keyId).containsOnly(KEY_ID);
    }

    @Test
    @DisplayName("복호화 결과에도 길이 검사를 적용한다 — 엉뚱한 키로 풀린 값이 통과하면 안 된다")
    void validates_decrypted_key_length() {
        KmsClient kms = kmsReturning(bytesOf(16), bytesOf(32));

        assertThatThrownBy(() -> new KmsCryptoKeyProvider(kms, props(KEY_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.aes-key")
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("key-id가 없으면 부팅을 멈춘다")
    void requires_key_id() {
        KmsClient kms = kmsReturning(bytesOf(32), bytesOf(32));

        assertThatThrownBy(() -> new KmsCryptoKeyProvider(kms, props(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.kms.key-id");
        assertThatThrownBy(() -> new KmsCryptoKeyProvider(kms, props("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crypto.kms.key-id");
    }

    @Test
    @DisplayName("암호문이 Base64가 아니면 KMS를 부르기 전에 멈춘다")
    void rejects_malformed_ciphertext_before_calling_kms() {
        KmsClient kms = mock(KmsClient.class);
        CryptoProperties broken = new CryptoProperties(
                "kms", "this is not base64!!", HMAC_CIPHERTEXT, null, null,
                new CryptoProperties.Kms(KEY_ID, null));

        assertThatThrownBy(() -> new KmsCryptoKeyProvider(kms, broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64");
        verify(kms, times(0)).decrypt(any(DecryptRequest.class));
    }

    @Test
    @DisplayName("KMS가 거절하면 원인 후보를 담아 부팅을 멈춘다")
    void surfaces_kms_failure_with_operator_hint() {
        KmsClient kms = mock(KmsClient.class);
        when(kms.decrypt(any(DecryptRequest.class)))
                .thenThrow(SdkException.builder().message("AccessDeniedException").build());

        assertThatThrownBy(() -> new KmsCryptoKeyProvider(kms, props(KEY_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crypto.aes-key")
                .hasMessageContaining("kms:Decrypt");
    }

    @Test
    @DisplayName("반환한 키 배열을 밖에서 고쳐도 내부 키는 그대로다")
    void returns_defensive_copies() {
        KmsClient kms = kmsReturning(bytesOf(32), bytesOf(32));
        KmsCryptoKeyProvider provider = new KmsCryptoKeyProvider(kms, props(KEY_ID));

        byte[] first = provider.getAesKey();
        first[0] = (byte) 0xFF;

        assertThat(provider.getAesKey()).isEqualTo(bytesOf(32));
        assertThatCode(() -> List.of(provider.getHmacKey())).doesNotThrowAnyException();
    }
}
