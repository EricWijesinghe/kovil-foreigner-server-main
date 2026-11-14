package com.kovil.infra.crypto;

import com.kovil.infra.crypto.exception.CryptoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.kovil.infra.crypto.exception.CryptoErrorCode.HMAC_HASHING_FAILED;

@Slf4j
@Component
public class HasherImpl implements Hasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final CryptoKeyProvider keyProvider;

    public HasherImpl(CryptoKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public String hash(String plainText) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyProvider.getHmacKey(), ALGORITHM));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("HMAC 해싱 실패", e);
            throw new CryptoException(HMAC_HASHING_FAILED);
        }
    }
}
