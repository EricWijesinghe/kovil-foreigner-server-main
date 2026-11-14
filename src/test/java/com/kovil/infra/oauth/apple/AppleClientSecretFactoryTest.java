package com.kovil.infra.oauth.apple;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 운영 .env 는 한 줄 값이라 .p8 의 개행이 \n 리터럴로 들어온다(FirebasePushSender 와 같은 함정).
// 그 값으로 client_secret 을 못 만들면 Apple 로그인과 탈퇴 철회가 전부 실패한다.
class AppleClientSecretFactoryTest {

    @Test
    @DisplayName("개행이 \\n 리터럴인 .p8 로도 client_secret 을 만든다 · sub 는 부른 client_id 다")
    void builds_secret_from_single_line_p8() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        String body = String.join("\\n", Base64.getEncoder().encodeToString(key.getPrivate().getEncoded())
                .split("(?<=\\G.{64})"));
        String singleLinePem = "-----BEGIN PRIVATE KEY-----\\n" + body + "\\n-----END PRIVATE KEY-----";
        Instant now = Instant.now();

        AppleClientSecretFactory factory = new AppleClientSecretFactory(new AppleOAuthProperties(true, "com.kovil.app",
                "com.kovil.web", "TEAMID1234", "KEYID12345", singleLinePem, null, null, null, null, null),
                Clock.fixed(now, ZoneOffset.UTC));
        String secret = factory.create("com.kovil.app");

        Jws<Claims> parsed = Jwts.parser().verifyWith(key.getPublic()).build().parseSignedClaims(secret);
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEYID12345");
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("TEAMID1234");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("com.kovil.app");
        assertThat(parsed.getPayload().getAudience()).containsExactly("https://appleid.apple.com");

        // 웹 교환 · 웹 토큰 철회의 secret 은 sub 가 Services ID 다. 같은 키로 만든다.
        Jws<Claims> web = Jwts.parser().verifyWith(key.getPublic()).build()
                .parseSignedClaims(factory.create("com.kovil.web"));
        assertThat(web.getPayload().getSubject()).isEqualTo("com.kovil.web");
    }

    // 부팅 검사는 빈 값만 본다. 깨진 base64 로 뜨면 로그인이 전부 500 이 되므로 키를 한 번 읽어 본다.
    @Test
    @DisplayName("키로 읽히지 않는 .p8 은 부팅에서 멈춘다 · 빈 값은 다른 부팅 검사에 맡긴다")
    void unreadable_key_fails_boot() {
        assertThatThrownBy(() -> factoryWith("-----BEGIN PRIVATE KEY-----\\nnot*base64\\n-----END PRIVATE KEY-----")
                .requireReadableKey())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> factoryWith(Base64.getEncoder().encodeToString("not a key".getBytes()))
                .requireReadableKey())
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> factoryWith("").requireReadableKey()).doesNotThrowAnyException();
        assertThatCode(() -> factoryWith(null).requireReadableKey()).doesNotThrowAnyException();
    }

    private static AppleClientSecretFactory factoryWith(String privateKey) {
        return new AppleClientSecretFactory(new AppleOAuthProperties(true, "com.kovil.app", "com.kovil.web",
                "TEAMID1234", "KEYID12345", privateKey, null, null, null, null, null), Clock.systemUTC());
    }
}
