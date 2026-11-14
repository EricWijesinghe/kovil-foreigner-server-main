package com.kovil.domain.auth.service.support;

import com.kovil.common.ratelimit.RateLimitException;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimitStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 성공은 세지 않고 실패만 센다 — peek(판정)과 record(기록)를 분리한 이유다.
// tryConsume을 쓰면 정상 로그인도 한도를 갉아먹는다.
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    private final RateLimitStore store;

    public void check(String email, String clientIp) {
        deny(store.peek(RateLimitPolicy.SIGNIN_FAIL_ID, emailKey(email)));
        deny(store.peek(RateLimitPolicy.SIGNIN_FAIL_IP, clientIp));
    }

    public void recordFailure(String email, String clientIp) {
        store.record(RateLimitPolicy.SIGNIN_FAIL_ID, emailKey(email));
        store.record(RateLimitPolicy.SIGNIN_FAIL_IP, clientIp);
    }

    private void deny(RateLimitStore.RateLimitResult result) {
        if (!result.allowed()) {
            throw new RateLimitException(result.retryAfterSeconds());
        }
    }

    // 이메일을 Redis 키에 평문으로 남기지 않는다.
    private String emailKey(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(email.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM은 존재하지 않는다", e);
        }
    }
}
