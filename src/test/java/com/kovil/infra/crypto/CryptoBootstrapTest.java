package com.kovil.infra.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoBootstrapTest {

    private static final String AES_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String HMAC_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Configuration
    @EnableConfigurationProperties(CryptoProperties.class)
    static class CryptoTestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CryptoTestConfig.class)
            .withBean(EncryptorImpl.class)
            .withBean(HasherImpl.class);

    private ApplicationContextRunner withEnvProvider() {
        return runner.withUserConfiguration(EnvCryptoKeyProvider.class);
    }

    @Test
    @DisplayName("키가 갖춰지면 Encryptor·Hasher가 뜨고 서로 같은 키를 본다")
    void boots_with_valid_keys() {
        withEnvProvider()
                .withPropertyValues(
                        "crypto.key-provider=env",
                        "crypto.aes-key=" + AES_KEY,
                        "crypto.hmac-key=" + HMAC_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CryptoKeyProvider.class);

                    Encryptor encryptor = context.getBean(Encryptor.class);
                    assertThat(encryptor.decrypt(encryptor.encrypt("01012345678"))).isEqualTo("01012345678");
                });
    }

    // 키 없이 뜨면 첫 가입자의 phone_hash가 잘못 계산되고, 그건 되돌릴 수 없다.
    // 부팅이 실패하는 편이 낫다.
    @Test
    @DisplayName("키가 없으면 컨텍스트가 뜨지 않는다")
    void fails_to_boot_without_keys() {
        withEnvProvider()
                .withPropertyValues("crypto.key-provider=env")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("crypto.aes-key"));
    }

    @Test
    @DisplayName("키가 규격에 맞지 않으면 컨텍스트가 뜨지 않는다")
    void fails_to_boot_with_undersized_key() {
        withEnvProvider()
                .withPropertyValues(
                        "crypto.key-provider=env",
                        "crypto.aes-key=" + Base64.getEncoder().encodeToString(new byte[16]),
                        "crypto.hmac-key=" + HMAC_KEY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("crypto.aes-key"));
    }

    // 프로바이더 이름이 어긋나면 암호화 없이 뜨는 게 아니라 부팅이 멈춰야 한다.
    // (kms 어댑터는 KmsCryptoKeyProvider 로 존재하지만, 이 러너는 env 만 등록하므로
    //  "선택된 프로바이더의 빈이 없으면 멈춘다"는 불변식을 그대로 검증한다.)
    @Test
    @DisplayName("선택한 프로바이더의 빈이 없으면 부팅이 멈춘다")
    void unknown_provider_leaves_no_key_provider() {
        withEnvProvider()
                .withPropertyValues(
                        "crypto.key-provider=kms",
                        "crypto.aes-key=" + AES_KEY,
                        "crypto.hmac-key=" + HMAC_KEY)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("No qualifying bean of type")
                        .hasMessageContaining("CryptoKeyProvider"));
    }
}
