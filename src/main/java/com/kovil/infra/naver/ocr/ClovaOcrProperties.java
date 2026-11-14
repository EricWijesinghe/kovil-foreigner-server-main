package com.kovil.infra.naver.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clova.ocr")
public record ClovaOcrProperties(String invokeUrl, String secretKey) {
}
