package com.kovil.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitStore {

    private static final String KEY_PREFIX = "ratelimit:";

    private static final DefaultRedisScript<List> CONSUME_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            if current > tonumber(ARGV[2]) then
              return {0, ttl}
            end
            return {1, ttl}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public RateLimitResult tryConsume(RateLimitPolicy policy, String key) {
        if (!properties.enabled()) {
            return RateLimitResult.allow();
        }
        RateLimitProperties.Rule rule = properties.ruleOf(policy);
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = redisTemplate.execute(
                    CONSUME_SCRIPT,
                    List.of(keyOf(policy, key)),
                    String.valueOf(rule.window().toSeconds()),
                    String.valueOf(rule.limit()));
            // 리미터의 어떤 이상도 차단(NPE→500)이 아니라 fail-open이어야 한다.
            if (result == null || result.size() < 2) {
                log.error("[ratelimit] Lua 응답 무효 — fail-open. policy={} result={}", policy, result);
                return RateLimitResult.allow();
            }
            return result.get(0) == 1L
                    ? RateLimitResult.allow()
                    : RateLimitResult.deny(result.get(1));
        } catch (DataAccessException e) {
            log.error("[ratelimit] Redis 장애 — fail-open. policy={} error={}", policy, e.getMessage());
            return RateLimitResult.allow();
        }
    }

    // 카운터를 소비하지 않고 현재 상태만 본다. 로그인 실패처럼 "성공하면 세지 않는" 축에 쓴다.
    public RateLimitResult peek(RateLimitPolicy policy, String key) {
        if (!properties.enabled()) {
            return RateLimitResult.allow();
        }
        RateLimitProperties.Rule rule = properties.ruleOf(policy);
        try {
            String redisKey = keyOf(policy, key);
            String current = redisTemplate.opsForValue().get(redisKey);
            if (current == null || Long.parseLong(current) < rule.limit()) {
                return RateLimitResult.allow();
            }
            Long ttl = redisTemplate.getExpire(redisKey);
            return RateLimitResult.deny(ttl == null || ttl < 0 ? rule.window().toSeconds() : ttl);
        } catch (DataAccessException | NumberFormatException e) {
            log.error("[ratelimit] Redis 장애 — fail-open. policy={} error={}", policy, e.getMessage());
            return RateLimitResult.allow();
        }
    }

    public void record(RateLimitPolicy policy, String key) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redisTemplate.execute(
                    CONSUME_SCRIPT,
                    List.of(keyOf(policy, key)),
                    String.valueOf(properties.ruleOf(policy).window().toSeconds()),
                    String.valueOf(Long.MAX_VALUE));
        } catch (DataAccessException e) {
            log.error("[ratelimit] Redis 장애 — 기록 유실 수용. policy={} error={}", policy, e.getMessage());
        }
    }

    public int limitOf(RateLimitPolicy policy) {
        return properties.ruleOf(policy).limit();
    }

    private String keyOf(RateLimitPolicy policy, String key) {
        return KEY_PREFIX + policy.name().toLowerCase() + ":" + key;
    }

    public record RateLimitResult(boolean allowed, long retryAfterSeconds) {
        public static RateLimitResult allow() {
            return new RateLimitResult(true, 0);
        }

        public static RateLimitResult deny(long retryAfterSeconds) {
            return new RateLimitResult(false, Math.max(retryAfterSeconds, 1));
        }
    }
}
