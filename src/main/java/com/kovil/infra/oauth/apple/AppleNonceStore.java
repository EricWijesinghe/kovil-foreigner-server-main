package com.kovil.infra.oauth.apple;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

// 네이티브 로그인의 재전송 방어. 앱이 이 값을 Apple 에 넘기면 id_token 의 nonce 클레임으로 돌아온다.
// OAuthStateStore 와 같은 규약이다. 한 번 쓰면 지우고, 검증 성공 여부와 무관하게 읽는 순간 지운다.
@Component
@RequiredArgsConstructor
public class AppleNonceStore {

    private static final String PREFIX = "oauth:apple-nonce:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int NONCE_BYTES = 32;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[NONCE_BYTES];
        secureRandom.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(PREFIX + nonce, "1", TTL);
        return nonce;
    }

    // 만료 · 재사용 · 위조는 전부 false 다.
    public boolean consume(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        return redisTemplate.opsForValue().getAndDelete(PREFIX + nonce) != null;
    }
}
