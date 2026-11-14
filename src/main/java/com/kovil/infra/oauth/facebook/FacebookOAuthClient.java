package com.kovil.infra.oauth.facebook;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.OAuthClient;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import com.kovil.infra.oauth.facebook.dto.FacebookTokenResponse;
import com.kovil.infra.oauth.facebook.dto.FacebookUserInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class FacebookOAuthClient implements OAuthClient {

    private static final String USER_FIELDS = "id,name,email";

    private final FacebookOAuthProperties properties;
    private final FacebookTokenClient tokenClient;
    private final FacebookUserInfoClient userInfoClient;

    @Override
    public AuthType provider() {
        return AuthType.FACEBOOK;
    }

    @Override
    public String buildAuthorizationUri(String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", "email")
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    @Override
    public OAuthUserInfo getUserInfo(String code, String state, String userPayload,
                                     String codeVerifier) {
        FacebookTokenResponse token = tokenClient.getToken(
                properties.clientId(), properties.clientSecret(), properties.redirectUri(), code);

        FacebookUserInfoResponse userInfo =
                userInfoClient.getUserInfo(USER_FIELDS, token.accessToken());
        return new OAuthUserInfo(userInfo.id(), userInfo.email(), userInfo.name());
    }
}
