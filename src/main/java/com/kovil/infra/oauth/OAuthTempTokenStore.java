package com.kovil.infra.oauth;

import com.kovil.infra.oauth.dto.OAuthTempData;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

// 소셜 신원을 휴대폰 인증이 끝날 때까지 들고 있는 자리. DB에 넣지 않는 이유:
// 인증을 끝내지 않은 시도가 테이블에 쌓이기 때문이다(FR-102와 같은 축).
@Component
@RequiredArgsConstructor
public class OAuthTempTokenStore {

    private static final String PREFIX = "oauth:temp:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JsonMapper jsonMapper;

    public String save(OAuthTempData data) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(PREFIX + token, data, TTL);
        return token;
    }

    public Optional<OAuthTempData> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Object raw = redisTemplate.opsForValue().get(PREFIX + token);
        return raw == null ? Optional.empty()
                : Optional.of(jsonMapper.convertValue(raw, OAuthTempData.class));
    }

    public void delete(String token) {
        redisTemplate.delete(PREFIX + token);
    }
}
