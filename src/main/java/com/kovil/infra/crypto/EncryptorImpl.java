package com.kovil.infra.crypto;

import com.kovil.infra.crypto.exception.CryptoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static com.kovil.infra.crypto.exception.CryptoErrorCode.AES_DECRYPT_FAILED;
import static com.kovil.infra.crypto.exception.CryptoErrorCode.AES_ENCRYPT_FAILED;

// 암호문 형식:  v{버전}:Base64( IV(12) || GCM 암호문+태그 )
//
// 버전을 앞에 붙이는 이유는 키 교체 때문이다. 키가 유출되면 새 키로 갈아야 하는데,
// 암호문이 "어느 키로 만들어졌는지" 말해주지 않으면 옛 키와 새 키가 공존할 수 없다.
// 그러면 교체는 "앱을 세우고 전량 변환"이 되고, 그건 사고 대응 중에 하기엔 나쁜 절차다.
// 접두사가 있으면 새 데이터는 새 키로 쓰면서 옛 데이터를 뒤에서 백필할 수 있다.
//
// 접두사가 없는 값은 v1 으로 읽는다 — 이 형식을 도입하기 전에 이미 저장된 데이터가 있다.
@Slf4j
@Component
public class EncryptorImpl implements Encryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final char VERSION_PREFIX = 'v';
    private static final char VERSION_SEPARATOR = ':';
    private static final int LEGACY_VERSION = 1;

    private final CryptoKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptorImpl(CryptoKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    @Override
    public String encrypt(String plainText) {
        int version = keyProvider.activeVersion();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyProvider.aesKey(version), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);

            return VERSION_PREFIX + Integer.toString(version) + VERSION_SEPARATOR
                    + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // CustomException은 cause를 담지 않는다. 여기서 남기지 않으면 원인이 사라진다.
            log.error("AES 암호화 실패", e);
            throw new CryptoException(AES_ENCRYPT_FAILED);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        try {
            int version = parseVersion(cipherText);
            byte[] combined = Base64.getDecoder().decode(stripVersion(cipherText));

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyProvider.aesKey(version), "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("AES 복호화 실패", e);
            throw new CryptoException(AES_DECRYPT_FAILED);
        }
    }

    // "v2:...." → 2. 접두사가 없으면 형식 도입 이전 데이터이므로 v1 이다.
    private static int parseVersion(String cipherText) {
        int separator = separatorIndex(cipherText);
        if (separator < 0) {
            return LEGACY_VERSION;
        }
        return Integer.parseInt(cipherText.substring(1, separator));
    }

    private static String stripVersion(String cipherText) {
        int separator = separatorIndex(cipherText);
        return separator < 0 ? cipherText : cipherText.substring(separator + 1);
    }

    // Base64 알파벳에 'v' 도 ':' 도 없다. 따라서 이 모양은 접두사로만 나타난다.
    private static int separatorIndex(String cipherText) {
        if (cipherText == null || cipherText.isEmpty() || cipherText.charAt(0) != VERSION_PREFIX) {
            return -1;
        }
        int separator = cipherText.indexOf(VERSION_SEPARATOR);
        return separator > 1 ? separator : -1;
    }
}
