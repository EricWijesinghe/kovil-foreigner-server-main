package com.kovil.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 브라우저에서 이 API를 부르는 출처 목록.
 *
 * <p>네이티브 앱(iOS·Android)은 Origin 헤더를 보내지 않아 CORS와 무관하다 — 이 설정이 필요한 쪽은
 * <b>Expo 웹·Next 관리자 웹</b>처럼 브라우저에서 도는 프런트뿐이다.
 *
 * <p>패턴 문법은 {@code CorsConfiguration.setAllowedOriginPatterns}를 따른다. 포트 와일드카드는
 * 대괄호다: {@code http://localhost:[*]} · {@code http://localhost:[8081,3000]}.
 * 콜론 하나로 쓴 {@code http://localhost:*}는 매칭되지 않는다.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        List<String> allowedOriginPatterns
) {
    public CorsProperties {
        allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
    }

    /** 비어 있으면 CORS 자체를 걸지 않는다 — 브라우저 프런트가 없는 배포에서 굳이 열지 않기 위해서다. */
    public boolean isEnabled() {
        return !allowedOriginPatterns.isEmpty();
    }
}
