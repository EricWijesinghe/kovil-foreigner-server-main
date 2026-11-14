package com.kovil.infra.crypto;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

@Configuration
@ConditionalOnProperty(name = "crypto.key-provider", havingValue = "kms")
public class KmsCryptoConfig {

    // 자격 증명을 주입하지 않는다 — EC2 인스턴스 역할로 붙는다(서버에 키가 남지 않는다).
    // 리전도 비워두면 SDK 기본 체인이 인스턴스 메타데이터에서 읽는다. 로컬에서 돌릴 때만
    // crypto.kms.region 으로 지정한다.
    @Bean
    public KmsClient cryptoKmsClient(CryptoProperties properties) {
        KmsClientBuilder builder = KmsClient.builder();
        String region = properties.kms() == null ? null : properties.kms().region();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.trim()));
        }
        return builder.build();
    }
}
