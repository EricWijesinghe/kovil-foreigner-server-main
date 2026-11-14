package com.kovil.infra.oauth.dto;

import com.kovil.domain.user.enums.AuthType;

// encryptedProviderRefreshToken · providerClientId 는 Apple 만 싣는다. Redis 에도 평문을 두지 않는다.
// 칸이 늘기 전에 저장된 증표(최대 10분)는 이 칸들이 null 로 읽힌다.
public record OAuthTempData(AuthType provider, String providerUserId, String email, String name,
                            String encryptedProviderRefreshToken, String providerClientId) {

    public OAuthTempData(AuthType provider, String providerUserId, String email, String name) {
        this(provider, providerUserId, email, name, null, null);
    }
}
