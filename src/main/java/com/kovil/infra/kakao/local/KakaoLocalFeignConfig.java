package com.kovil.infra.kakao.local;

import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;

@RequiredArgsConstructor
public class KakaoLocalFeignConfig {

    private final KakaoLocalProperties kakaoLocalProperties;

    @Bean
    public RequestInterceptor kakaoLocalRequestInterceptor() {
        return template -> template.header("Authorization", "KakaoAK " +
                kakaoLocalProperties.restApiKey());
    }

    @Bean
    public KakaoLocalErrorDecoder kakaoLocalErrorDecoder() {
        return new KakaoLocalErrorDecoder();
    }

}
