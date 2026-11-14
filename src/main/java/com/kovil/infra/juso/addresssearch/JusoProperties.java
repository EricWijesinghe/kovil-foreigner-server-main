package com.kovil.infra.juso.addresssearch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "juso.search")
public record JusoProperties(String baseUrl, String confmKey) {
}
