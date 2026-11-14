package com.kovil.infra.oauth;

import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.user.enums.AuthType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_PROVIDER_DISABLED;
import static com.kovil.domain.auth.exception.AuthErrorCode.UNSUPPORTED_OAUTH_PROVIDER;

// 선례는 클라이언트를 필드로 받아 switch 하지만, 우리는 fake/real을 @ConditionalOnProperty로
// 가르므로 특정 빈이 없을 수 있다. 존재하는 빈만 모아 맵을 만든다.
//
// 허용 목록(enabled-providers)은 빈 존재 여부와 별개의 축이다 — 페이크 모드에서도 잠긴 수단은
// 잠겨야 화면·테스트가 운영과 같은 현실을 본다(애플: 개발자 계정 확보 전까지 잠금).
@Component
public class OAuthClientRouter {

    private final Map<AuthType, OAuthClient> clients = new EnumMap<>(AuthType.class);
    private final OAuthProviderProperties properties;

    public OAuthClientRouter(List<OAuthClient> oAuthClients, OAuthProviderProperties properties) {
        oAuthClients.forEach(client -> clients.put(client.provider(), client));
        this.properties = properties;
    }

    public OAuthClient getClient(AuthType provider) {
        if (properties.enabledProviders() == null || !properties.enabledProviders().contains(provider)) {
            throw new OAuthException(OAUTH_PROVIDER_DISABLED);
        }
        OAuthClient client = clients.get(provider);
        if (client == null) {
            throw new OAuthException(UNSUPPORTED_OAUTH_PROVIDER);
        }
        return client;
    }
}
