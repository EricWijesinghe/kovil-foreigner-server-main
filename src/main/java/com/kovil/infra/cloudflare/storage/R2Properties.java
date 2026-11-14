package com.kovil.infra.cloudflare.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudflare.r2")
public record R2Properties(
        String accountId,
        String accessKeyId,
        String secretAccessKey,
        String bucket,
        String publicBaseUrl
) {
    public String endpoint() {
        return "https://" + accountId + ".r2.cloudflarestorage.com";
    }
}
