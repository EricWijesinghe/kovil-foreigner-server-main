package com.kovil.infra.oauth.apple;

import org.springframework.boot.context.properties.ConfigurationProperties;

// ⚠ Apple만 clientSecret이 문자열이 아니다. 팀ID·키ID·.p8 개인키로 매 요청 ES256 JWT를 만든다.
//
// client_id 가 둘이다. 앱 네이티브는 번들 ID(clientId), 웹은 Services ID(webClientId)다.
// id_token 의 aud, client_secret 의 sub, 토큰 철회의 client_id 가 모두 그 토큰을 낸 쪽 값이어야 한다.
// 둘은 같은 primary App ID 에 묶여 있어 sub(사용자 식별자)가 같다.
// 여닫는 축도 둘이다. 네이티브는 nativeEnabled, 웹은 oauth.enabled-providers 의 APPLE 이다.
@ConfigurationProperties(prefix = "oauth.apple")
public record AppleOAuthProperties(
        boolean nativeEnabled,
        String clientId,
        String webClientId,
        String teamId,
        String keyId,
        String privateKey,
        String redirectUri,
        String authorizationUri,
        String tokenUri,
        String keysUri,
        String revokeUri
) {
}
