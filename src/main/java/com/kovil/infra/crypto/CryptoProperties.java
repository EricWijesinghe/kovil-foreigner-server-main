package com.kovil.infra.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crypto")
public record CryptoProperties(
        String keyProvider,
        // 현재 키. env 면 Base64(원문), kms 면 Base64(KMS 암호문)다. 같은 칸을 재사용하는 이유는
        // 잘못 넣었을 때 조용히 통과하지 않기 때문이다 — 원문을 kms 모드에 넣으면 KMS가 거절하고,
        // 암호문을 env 모드에 넣으면 32바이트 길이 검사에 걸린다.
        String aesKey,
        String hmacKey,
        Integer activeVersion,
        // 교체 중에만 채운다. 채워지면 옛 버전 암호문도 읽을 수 있고, 백필이 끝나면 비운다.
        Previous previous,
        Kms kms
) {
    public record Previous(Integer version, String aesKey, String hmacKey) {
        boolean isConfigured() {
            return notBlank(aesKey) || notBlank(hmacKey) || version != null;
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Kms(String keyId, String region) {
    }
}
