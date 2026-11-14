package com.kovil.infra.kakao.geocoding;

import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;

// @Configuration을 붙이지 않는다 — 붙이면 전역 Feign 설정이 되어 다른 클라이언트에도 샌다.
@RequiredArgsConstructor
public class KakaoGeocodingFeignConfig {

    private final KakaoGeocodingProperties properties;

    @Bean
    public RequestInterceptor kakaoGeocodingAuthInterceptor() {
        return template -> template.header("Authorization", "KakaoAK " + properties.restApiKey());
    }

    @Bean
    public KakaoGeocodingErrorDecoder kakaoGeocodingErrorDecoder() {
        return new KakaoGeocodingErrorDecoder();
    }
}
