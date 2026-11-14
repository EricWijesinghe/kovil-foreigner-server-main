package com.kovil.common.config;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.FakeOAuthClient;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import com.kovil.infra.oauth.apple.FakeAppleIdTokenVerifier;
import com.kovil.infra.oauth.apple.FakeAppleTokenExchanger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// provider 앱 등록(Apple 팀ID·.p8, Google/Facebook 클라이언트 시크릿)이 끝나기 전에도
// 소셜 연결 흐름을 돌려볼 수 있어야 한다. 기본값이 fake인 이유다.
//
// Apple 은 웹 흐름(OAuthClient)과 앱 네이티브 로그인(검증기 · 교환기)이 따로 있어 둘 다 가짜를 둔다.
@Configuration
@ConditionalOnProperty(name = "oauth.provider", havingValue = "fake", matchIfMissing = true)
public class FakeOAuthConfig {

    @Bean
    public OAuthClient fakeAppleOAuthClient() {
        return new FakeOAuthClient(AuthType.APPLE);
    }

    @Bean
    public OAuthClient fakeGoogleOAuthClient() {
        return new FakeOAuthClient(AuthType.GOOGLE);
    }

    @Bean
    public OAuthClient fakeFacebookOAuthClient() {
        return new FakeOAuthClient(AuthType.FACEBOOK);
    }

    @Bean
    public AppleIdTokenVerifier fakeAppleIdTokenVerifier() {
        return new FakeAppleIdTokenVerifier();
    }

    @Bean
    public AppleTokenExchanger fakeAppleTokenExchanger() {
        return new FakeAppleTokenExchanger();
    }
}
