package com.kovil.infra.naver.geocoding;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 반드시 2025-07 신상품 Maps(Application Services > Maps)의 Application 키여야 한다.
// 구 AI·NAVER API(naveropenapi.apigw.ntruss.com) 키는 신규 차단 + 무료 없음 — 섞이면 429/401만 본다.
@ConfigurationProperties(prefix = "naver.geocoding")
public record NaverGeocodingProperties(String baseUrl, String clientId, String clientSecret) {
}
