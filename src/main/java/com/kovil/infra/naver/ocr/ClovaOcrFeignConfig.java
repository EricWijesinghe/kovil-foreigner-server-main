package com.kovil.infra.naver.ocr;

import feign.RequestInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;

// @Configuration을 붙이지 않는다 — 붙이면 전역 Feign 설정이 되어 다른 클라이언트에도 샌다.
@RequiredArgsConstructor
public class ClovaOcrFeignConfig {

    private final ClovaOcrProperties properties;

    @Bean
    public RequestInterceptor clovaOcrAuthInterceptor() {
        return template -> template.header("X-OCR-SECRET", properties.secretKey());
    }

    @Bean
    public ClovaOcrErrorDecoder clovaOcrErrorDecoder() {
        return new ClovaOcrErrorDecoder();
    }
}
