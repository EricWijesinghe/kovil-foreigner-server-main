package com.kovil.infra.kakao.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.local")
public record KakaoLocalProperties(
        String baseUrl,
        String restApiKey
) {
}
