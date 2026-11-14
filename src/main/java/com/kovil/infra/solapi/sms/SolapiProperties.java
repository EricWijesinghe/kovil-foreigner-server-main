package com.kovil.infra.solapi.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solapi")
public record SolapiProperties(
        String apiKey,
        String apiSecret,
        String from
) {
}
