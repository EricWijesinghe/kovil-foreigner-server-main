package com.kovil.infra.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 웹(kovil.co.kr)으로 돌아갈 때의 주소. 되돌림 페이지는 {baseUrl}/oauth/{provider} 로 보낸다.
// 로컬 개발은 Expo 웹 주소(http://localhost:8081)를 넣는다. 끝의 / 는 떼어 둔다.
@ConfigurationProperties(prefix = "oauth.web")
public record OAuthWebProperties(String baseUrl) {

    private static final String DEFAULT_BASE_URL = "https://kovil.co.kr";

    // yaml 의 기본값은 변수가 아예 없을 때만 쓰인다. env 파일에 `OAUTH_WEB_BASE_URL=` 처럼 빈 줄이 있으면
    // 빈 문자열이 들어와 부팅이 멈추므로 여기서 운영 기본값으로 돌린다.
    public OAuthWebProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (!(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw new IllegalStateException("oauth.web.base-url 은 http(s):// 로 시작하는 주소여야 한다");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }
}
