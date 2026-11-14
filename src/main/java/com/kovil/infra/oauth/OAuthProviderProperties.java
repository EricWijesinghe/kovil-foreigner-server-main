package com.kovil.infra.oauth;

import com.kovil.domain.user.enums.AuthType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// 켜져 있는 소셜 수단의 허용 목록. 애플은 개발자 계정(유료) 확보 전까지 잠근다 —
// 코드·어댑터는 그대로 두고 이 목록으로만 여닫는다(PO 확정 2026-08-21, 재도입 예정).
@ConfigurationProperties(prefix = "oauth")
public record OAuthProviderProperties(List<AuthType> enabledProviders) {
}
