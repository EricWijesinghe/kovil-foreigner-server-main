package com.kovil.infra.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(String provider, long presignTtlSeconds, long readUrlTtlSeconds) {
}
