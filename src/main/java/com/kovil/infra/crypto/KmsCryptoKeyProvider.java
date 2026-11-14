package com.kovil.infra.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;

// 엔벨로프 암호화 — 평문 키는 어디에도 저장되지 않는다. .env·GitHub 시크릿·docker inspect 에는
// KMS 암호문만 남고, 부팅 때 kms:Decrypt 를 **키당 1회** 호출해 메모리에 올린다.
// 그 뒤의 AES·HMAC 연산은 env 프로바이더와 완전히 동일하게 로컬에서 계산된다.
//
// ★ KMS 에게 암호 연산 자체를 시키지 않는 이유: HasherImpl 의 HMAC 결과가 phone_hash
//   블라인드 인덱스다(휴대폰 번호로 회원을 찾는 조회 키). KMS GenerateMac 으로 옮기면
//   가입·로그인·조회마다 네트워크 왕복이 붙고, KMS 가 흔들리면 로그인이 죽는다.
//   키를 보호하려는 것이지 연산을 옮기려는 것이 아니다.
@Component
@ConditionalOnProperty(name = "crypto.key-provider", havingValue = "kms")
public class KmsCryptoKeyProvider implements CryptoKeyProvider {

    private final CryptoKeyProvider keys;

    public KmsCryptoKeyProvider(KmsClient kmsClient, CryptoProperties properties) {
        String keyId = requireKeyId(properties);
        this.keys = VersionedCryptoKeys.of(properties,
                (propertyName, value) -> decrypt(kmsClient, keyId, propertyName, value));
    }

    // keyId 는 Decrypt 에 필수가 아니다(암호문이 키를 품고 있다). 그럼에도 넘기는 이유는
    // KMS 가 "이 암호문이 정말 그 키로 만들어졌는지"까지 확인해 주기 때문이다 —
    // 암호문이 통째로 바꿔치기되어도 다른 키로는 복호화되지 않는다.
    private static String requireKeyId(CryptoProperties properties) {
        String keyId = properties.kms() == null ? null : properties.kms().keyId();
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException(
                    "crypto.kms.key-id is required when crypto.key-provider=kms");
        }
        return keyId.trim();
    }

    private static byte[] decrypt(KmsClient kmsClient, String keyId, String propertyName, String ciphertextBase64) {
        byte[] ciphertext = CryptoKeys.decodeBase64(propertyName, ciphertextBase64);
        try {
            return kmsClient.decrypt(DecryptRequest.builder()
                            .keyId(keyId)
                            .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                            .build())
                    .plaintext()
                    .asByteArray();
        } catch (SdkException e) {
            // 부팅 로그에 남는 유일한 단서다. 원인이 셋뿐이라 그대로 적는다.
            throw new IllegalStateException(
                    propertyName + " KMS decrypt failed — 암호문이 " + keyId + " 로 만들어졌는지, "
                            + "인스턴스 역할에 kms:Decrypt 권한이 있는지, 리전이 맞는지 확인할 것", e);
        }
    }

    @Override
    public int activeVersion() {
        return keys.activeVersion();
    }

    @Override
    public byte[] aesKey(int version) {
        return keys.aesKey(version);
    }

    @Override
    public byte[] hmacKey(int version) {
        return keys.hmacKey(version);
    }
}
