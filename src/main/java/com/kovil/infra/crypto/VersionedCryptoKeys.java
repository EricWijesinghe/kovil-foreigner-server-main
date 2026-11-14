package com.kovil.infra.crypto;

import java.util.LinkedHashMap;
import java.util.Map;

// 버전별 키 보관. env·kms 프로바이더가 키를 어떻게 얻든 이 규칙은 하나다.
// 동시에 보유하는 버전은 최대 둘(현재 + 직전)이다 — 교체는 "새 키로 갈고, 백필하고,
// 옛 키를 뗀다"의 반복이라 셋 이상을 들고 있을 이유가 없다. 늘리면 어느 데이터가
// 어느 키로 묶여 있는지 사람이 추적하지 못한다.
final class VersionedCryptoKeys implements CryptoKeyProvider {

    private final int activeVersion;
    private final Map<Integer, byte[]> aesKeys;
    private final Map<Integer, byte[]> hmacKeys;

    private VersionedCryptoKeys(int activeVersion, Map<Integer, byte[]> aesKeys, Map<Integer, byte[]> hmacKeys) {
        this.activeVersion = activeVersion;
        this.aesKeys = aesKeys;
        this.hmacKeys = hmacKeys;
    }

    // decoder 는 "설정 문자열 → 원문 키 바이트". env 는 Base64 디코드, kms 는 복호화다.
    static VersionedCryptoKeys of(CryptoProperties properties, KeyMaterialDecoder decoder) {
        int active = properties.activeVersion() == null ? 1 : properties.activeVersion();
        if (active < 1) {
            throw new IllegalArgumentException("crypto.active-version must be >= 1, but was " + active);
        }

        Map<Integer, byte[]> aes = new LinkedHashMap<>();
        Map<Integer, byte[]> hmac = new LinkedHashMap<>();
        aes.put(active, CryptoKeys.requireAesKey("crypto.aes-key", decoder.decode("crypto.aes-key", properties.aesKey())));
        hmac.put(active, CryptoKeys.requireHmacKey("crypto.hmac-key", decoder.decode("crypto.hmac-key", properties.hmacKey())));

        CryptoProperties.Previous previous = properties.previous();
        if (previous != null && previous.isConfigured()) {
            Integer version = previous.version();
            if (version == null) {
                throw new IllegalArgumentException(
                        "crypto.previous.version is required when a previous key is configured");
            }
            if (version.equals(active)) {
                throw new IllegalArgumentException(
                        "crypto.previous.version must differ from crypto.active-version (both " + active + ")");
            }
            aes.put(version, CryptoKeys.requireAesKey(
                    "crypto.previous.aes-key", decoder.decode("crypto.previous.aes-key", previous.aesKey())));
            hmac.put(version, CryptoKeys.requireHmacKey(
                    "crypto.previous.hmac-key", decoder.decode("crypto.previous.hmac-key", previous.hmacKey())));
        }
        return new VersionedCryptoKeys(active, aes, hmac);
    }

    @Override
    public int activeVersion() {
        return activeVersion;
    }

    @Override
    public byte[] aesKey(int version) {
        return copyOrThrow(aesKeys, version, "AES");
    }

    @Override
    public byte[] hmacKey(int version) {
        return copyOrThrow(hmacKeys, version, "HMAC");
    }

    // 모르는 버전의 암호문을 만나면 조용히 현재 키로 시도하지 않는다. 그러면 복호화가
    // 실패하는 게 아니라 **엉뚱한 평문**이 나올 수 있다(GCM 태그가 막아주지만 의도가 흐려진다).
    private byte[] copyOrThrow(Map<Integer, byte[]> keys, int version, String kind) {
        byte[] key = keys.get(version);
        if (key == null) {
            throw new IllegalStateException(
                    kind + " key version " + version + " is not configured (have " + keys.keySet() + ")"
                            + " — 교체 중이라면 crypto.previous.* 를 채워야 한다");
        }
        return key.clone();
    }

    @FunctionalInterface
    interface KeyMaterialDecoder {
        byte[] decode(String propertyName, String configuredValue);
    }
}
