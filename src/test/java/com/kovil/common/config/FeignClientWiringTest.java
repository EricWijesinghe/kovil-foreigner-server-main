package com.kovil.common.config;

import com.kovil.infra.naver.ocr.ClovaOcrFeignClient;
import com.kovil.infra.kakao.geocoding.KakaoGeocodingFeignClient;
import com.kovil.infra.kakao.local.KakaoNearbyPlaceClient;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.naver.geocoding.NaverGeocodingFeignClient;
import com.kovil.infra.openai.llm.OpenAiFeignClient;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifierImpl;
import com.kovil.infra.oauth.apple.AppleKeysClient;
import com.kovil.infra.oauth.apple.AppleOAuthClient;
import com.kovil.infra.oauth.apple.AppleRevokeClient;
import com.kovil.infra.oauth.apple.AppleTokenClient;
import com.kovil.infra.oauth.apple.AppleTokenExchanger;
import com.kovil.infra.oauth.apple.AppleTokenExchangerImpl;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.facebook.FacebookTokenClient;
import com.kovil.infra.oauth.google.GoogleTokenClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// ★ 실측 중 발견: @EnableFeignClients가 없어 Feign 클라이언트가 하나도 등록되지 않았다.
//
// 기본값이 fake라 개발·테스트에서는 드러나지 않는다 — 실제 어댑터가 생성되지 않으니
// 없는 빈을 아무도 요구하지 않기 때문이다.
// **OAUTH_PROVIDER=real로 켜는 순간 컨텍스트가 뜨지 않는다.**
// 그래서 real 프로바이더로 컨텍스트를 띄워 배선을 확인한다.
@SpringBootTest
@TestPropertySource(properties = {
        "oauth.provider=real",
        "ocr.provider=clova",
        "llm.provider=openai",
        "openai.api-url=https://api.openai.invalid",
        "openai.api-key=dummy",
        "openai.model=gpt-5.4-mini",
        "oauth.google.token-uri=https://oauth2.googleapis.invalid/token",
        "oauth.google.user-info-uri=https://openidconnect.googleapis.invalid/v1/userinfo",
        "oauth.apple.token-uri=https://appleid.apple.invalid/auth/token",
        // 테스트 프로파일은 네이티브 · 웹을 다 켠다. 켜진 real 모드는 값이 비면 부팅을 멈추므로 더미를 넣는다.
        "oauth.apple.client-id=com.kovil.app",
        "oauth.apple.web-client-id=com.kovil.web",
        "oauth.apple.redirect-uri=https://api.kovil.invalid/oauth/apple/callback",
        "oauth.apple.team-id=TEAMID1234",
        "oauth.apple.key-id=KEYID12345",
        "oauth.apple.keys-uri=https://appleid.apple.invalid/auth/keys",
        "oauth.apple.revoke-uri=https://appleid.apple.invalid/auth/revoke",
        "oauth.facebook.token-uri=https://graph.facebook.invalid/v21.0/oauth/access_token",
        "oauth.facebook.user-info-uri=https://graph.facebook.invalid/v21.0/me",
        "clova.ocr.invoke-url=https://clova.invalid/general",
        "clova.ocr.secret-key=dummy",
        "geocoding.provider=naver",
        "naver.geocoding.base-url=https://naver-geocoding.invalid",
        "naver.geocoding.client-id=dummy",
        "naver.geocoding.client-secret=dummy",
        "kakao.geocoding.base-url=https://kakao-geocoding.invalid",
        "kakao.geocoding.rest-api-key=dummy",
        "nearby.provider=kakao",
        "kakao.local.base-url=https://kakao-local.invalid",
        "kakao.local.rest-api-key=dummy"
})
class FeignClientWiringTest {

    // 비밀 키 팩토리는 부팅 때 키를 한 번 읽어 본다. 더미 문자열로는 컨텍스트가 뜨지 않아 시험마다 EC 키를 만든다.
    @DynamicPropertySource
    static void applePrivateKey(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String key = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        registry.add("oauth.apple.private-key", () -> key);
    }

    @Autowired
    List<OAuthClient> oauthClients;

    @Autowired
    GoogleTokenClient googleTokenClient;

    @Autowired
    AppleTokenClient appleTokenClient;

    @Autowired
    AppleKeysClient appleKeysClient;

    @Autowired
    AppleRevokeClient appleRevokeClient;

    @Autowired
    AppleIdTokenVerifier appleIdTokenVerifier;

    @Autowired
    AppleTokenExchanger appleTokenExchanger;

    @Autowired
    FacebookTokenClient facebookTokenClient;

    @Autowired
    ClovaOcrFeignClient clovaOcrFeignClient;

    @Autowired
    OpenAiFeignClient openAiFeignClient;

    @Autowired
    NaverGeocodingFeignClient naverGeocodingFeignClient;

    @Autowired
    KakaoGeocodingFeignClient kakaoGeocodingFeignClient;

    @Autowired
    NearbyPlaceClient nearbyPlaceClient;

    @Test
    @DisplayName("real 프로바이더로도 컨텍스트가 뜨고 Feign 클라이언트가 전부 주입된다")
    void real_providers_wire_up() {
        assertThat(googleTokenClient).isNotNull();
        assertThat(appleTokenClient).isNotNull();
        assertThat(facebookTokenClient).isNotNull();
        assertThat(clovaOcrFeignClient).isNotNull();
        assertThat(openAiFeignClient).isNotNull();
        assertThat(naverGeocodingFeignClient).isNotNull();
        assertThat(kakaoGeocodingFeignClient).isNotNull();
        // 주변시설 — NEARBY_PROVIDER=kakao 로 켜면 카카오 로컬 Feign 위의 어댑터가 올라와야 한다.
        assertThat(nearbyPlaceClient).isInstanceOf(KakaoNearbyPlaceClient.class);
        // 페이크가 아니라 실제 어댑터가 올라와야 한다.
        assertThat(oauthClients).isNotEmpty();
    }

    @Test
    @DisplayName("real 에서 Apple 은 네이티브 검증기 · 교환기와 웹 흐름 어댑터가 함께 올라온다")
    void apple_native_and_web_wire_up() {
        assertThat(appleKeysClient).isNotNull();
        assertThat(appleRevokeClient).isNotNull();
        assertThat(appleIdTokenVerifier).isInstanceOf(AppleIdTokenVerifierImpl.class);
        assertThat(appleTokenExchanger).isInstanceOf(AppleTokenExchangerImpl.class);
        assertThat(oauthClients).filteredOn(client -> client.provider() == AuthType.APPLE)
                .singleElement().isInstanceOf(AppleOAuthClient.class);
    }
}
