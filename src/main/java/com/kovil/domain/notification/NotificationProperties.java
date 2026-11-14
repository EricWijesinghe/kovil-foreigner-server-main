package com.kovil.domain.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "notification.worker")
public record NotificationProperties(int maxAttempts, Duration retryBackoff) {
}
