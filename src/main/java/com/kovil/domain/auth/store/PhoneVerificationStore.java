package com.kovil.domain.auth.store;

import com.kovil.domain.auth.enums.VerificationPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

// 인증번호·시도 횟수·재발송 쿨다운·발급 토큰은 전부 Redis가 소유한다(FR-102).
// 키는 항상 phone_hash다 — 평문 번호를 Redis에 남기지 않는다.
@Component
@RequiredArgsConstructor
public class PhoneVerificationStore {

    private static final String CODE_PREFIX = "phone:code:";
    private static final String ATTEMPT_PREFIX = "phone:attempt:";
    private static final String COOLDOWN_PREFIX = "phone:cooldown:";
    private static final String TOKEN_PREFIX = "phone:token:";

    private final StringRedisTemplate redisTemplate;

    public void saveCode(String phoneHash, VerificationPurpose purpose, String code, Duration ttl) {
        redisTemplate.opsForValue().set(codeKey(phoneHash, purpose), code, ttl);
        redisTemplate.delete(attemptKey(phoneHash, purpose));
    }

    public Optional<String> findCode(String phoneHash, VerificationPurpose purpose) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(codeKey(phoneHash, purpose)));
    }

    // 코드와 시도 카운터를 함께 지운다. 코드를 남겨두면 폐기가 성립하지 않는다.
    public void discardCode(String phoneHash, VerificationPurpose purpose) {
        redisTemplate.delete(codeKey(phoneHash, purpose));
        redisTemplate.delete(attemptKey(phoneHash, purpose));
    }

    // 시도 카운터의 TTL은 코드와 같이 간다. 코드가 살아 있는 동안만 의미가 있다.
    public long increaseAttempt(String phoneHash, VerificationPurpose purpose, Duration ttl) {
        String key = attemptKey(phoneHash, purpose);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1L) {
            redisTemplate.expire(key, ttl);
        }
        return attempts == null ? 1L : attempts;
    }

    public boolean isCoolingDown(String phoneHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + phoneHash));
    }

    public void startCooldown(String phoneHash, Duration cooldown) {
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + phoneHash, "1", cooldown);
    }

    public void saveToken(String token, Long verificationId, Duration ttl) {
        redisTemplate.opsForValue().set(TOKEN_PREFIX + token, String.valueOf(verificationId), ttl);
    }

    public Optional<Long> findVerificationIdByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
        return value == null ? Optional.empty() : Optional.of(Long.valueOf(value));
    }

    public void deleteToken(String token) {
        redisTemplate.delete(TOKEN_PREFIX + token);
    }

    private String codeKey(String phoneHash, VerificationPurpose purpose) {
        return CODE_PREFIX + purpose.name().toLowerCase() + ":" + phoneHash;
    }

    private String attemptKey(String phoneHash, VerificationPurpose purpose) {
        return ATTEMPT_PREFIX + purpose.name().toLowerCase() + ":" + phoneHash;
    }
}
