package com.kovil.infra.naver.geocoding;

import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;

// @Configuration을 붙이지 않는다 — 붙이면 전역 Feign 설정이 되어 다른 클라이언트에도 샌다.
@RequiredArgsConstructor
public class NaverGeocodingFeignConfig {

    private final NaverGeocodingProperties properties;

    @Bean
    public RequestInterceptor naverGeocodingAuthInterceptor() {
        return template -> template
                .header("x-ncp-apigw-api-key-id", properties.clientId())
                .header("x-ncp-apigw-api-key", properties.clientSecret())
                .header("Accept", "application/json");
    }

    @Bean
    public NaverGeocodingErrorDecoder naverGeocodingErrorDecoder() {
        return new NaverGeocodingErrorDecoder();
    }
}
