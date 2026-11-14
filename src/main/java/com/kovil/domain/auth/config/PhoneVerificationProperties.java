package com.kovil.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "phone-verification")
public record PhoneVerificationProperties(
        Duration codeTtl,
        Duration resendCooldown,
        int maxAttempts,
        Duration tokenTtl
) {
}
