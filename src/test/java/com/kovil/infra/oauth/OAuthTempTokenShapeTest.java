package com.kovil.infra.oauth;

import com.kovil.domain.user.enums.AuthType;
import com.kovil.infra.oauth.dto.OAuthTempData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 임시 증표에 칸이 늘었다. 배포 순간 Redis 에 남아 있는 옛 모양(최대 10분)이 읽히지 않으면
// 휴대폰 인증까지 마친 사람이 OAUTH_TEMP_TOKEN_EXPIRED 가 아니라 500 을 받는다.
class OAuthTempTokenShapeTest {

    @Test
    @DisplayName("토큰 칸이 없는 옛 증표도 읽히고 그 칸은 null 이다")
    @SuppressWarnings("unchecked")
    void old_shape_reads_with_null_token() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(values);
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("provider", "GOOGLE");
        old.put("providerUserId", "google-sub-1");
        old.put("email", "a@example.test");
        old.put("name", null);
        given(values.get("oauth:temp:t-1")).willReturn(old);

        OAuthTempData data = new OAuthTempTokenStore(redisTemplate, JsonMapper.builder().build())
                .find("t-1").orElseThrow();

        assertThat(data.provider()).isEqualTo(AuthType.GOOGLE);
        assertThat(data.providerUserId()).isEqualTo("google-sub-1");
        assertThat(data.encryptedProviderRefreshToken()).isNull();
        assertThat(data.providerClientId()).isNull();
    }
}
