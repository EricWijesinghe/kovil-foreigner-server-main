package com.kovil.infra.oauth.apple;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "oauth.provider", havingValue = "real")
public class AppleClientSecretFactory {

    private static final String AUDIENCE = "https://appleid.apple.com";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final AppleOAuthProperties properties;
    private final Clock clock;

    // 빈 값은 네이티브 · 웹 쪽 부팅 검사가 본다. 여기는 값이 있는데 키로 읽히지 않는 경우다.
    // 그대로 뜨면 모든 Apple 로그인이 500 이 되고 탈퇴 철회는 로그만 남기고 실패하므로 부팅에서 멈춘다.
    @PostConstruct
    void requireReadableKey() {
        if (properties.privateKey() != null && !properties.privateKey().isBlank()) {
            privateKey();
        }
    }

    // sub 는 이 secret 으로 부를 client_id 다. 네이티브는 번들 ID, 웹은 Services ID 로 따로 만든다.
    public String create(String clientId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(properties.keyId()).and()
                .issuer(properties.teamId())
                .subject(clientId)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(privateKey(), Jwts.SIG.ES256)
                .compact();
    }

    // .env 한 줄 값은 개행이 \n 리터럴로 온다(FirebasePushSender 와 같은 함정). 공백류와 함께 걷어 낸다.
    private PrivateKey privateKey() {
        try {
            String pem = properties.privateKey()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("oauth.apple.private-key를 EC 개인키로 읽을 수 없습니다", e);
        }
    }
}
