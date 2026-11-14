package com.kovil.infra.cloudflare.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// R2는 S3 호환이라 AWS SDK를 그대로 쓴다. region은 R2가 무시하지만 SDK가 요구하므로 auto를 넣는다.
@Configuration
@ConditionalOnProperty(name = "storage.provider", havingValue = "r2")
public class R2StorageConfig {

    @Bean
    public S3Client r2S3Client(R2Properties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(properties))
                .build();
    }

    @Bean
    public S3Presigner r2S3Presigner(R2Properties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(properties))
                .build();
    }

    private StaticCredentialsProvider credentials(R2Properties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
    }
}
