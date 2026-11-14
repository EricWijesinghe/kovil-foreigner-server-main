package com.kovil.infra.kakao.geocoding;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 카카오 로컬 REST 키(KakaoAK). wonjoomin(Billigo)이 실사용하던 발급분을 공유한다 — PO 지시 2026-08-20.
@ConfigurationProperties(prefix = "kakao.geocoding")
public record KakaoGeocodingProperties(String baseUrl, String restApiKey) {
}
