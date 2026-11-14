package com.kovil.domain.device.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "device")
public record DeviceProperties(int maxTokensPerUser) {
}
