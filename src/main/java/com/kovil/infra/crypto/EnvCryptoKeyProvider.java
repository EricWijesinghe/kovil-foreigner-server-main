package com.kovil.infra.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crypto.key-provider", havingValue = "env", matchIfMissing = true)
public class EnvCryptoKeyProvider implements CryptoKeyProvider {

    private final CryptoKeyProvider keys;

    public EnvCryptoKeyProvider(CryptoProperties properties) {
        this.keys = VersionedCryptoKeys.of(properties, CryptoKeys::decodeBase64);
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
