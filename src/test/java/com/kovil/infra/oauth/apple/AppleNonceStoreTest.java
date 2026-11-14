package com.kovil.infra.oauth.apple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// nonce 가 두 번 통하면 가로챈 identityToken 을 다시 보내 로그인할 수 있다.
@ExtendWith(MockitoExtension.class)
class AppleNonceStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private AppleNonceStore store() {
        return new AppleNonceStore(redisTemplate);
    }

    @Test
    @DisplayName("32바이트 난수를 패딩 없는 base64url 로 주고 10분 동안 둔다")
    void generates_random_nonce_with_ttl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        String first = store().generate();
        String second = store().generate();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(first).isNotEqualTo(second);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        then(valueOperations).should(org.mockito.Mockito.times(2))
                .set(key.capture(), eq("1"), eq(Duration.ofMinutes(10)));
        assertThat(key.getAllValues()).containsExactly("oauth:apple-nonce:" + first, "oauth:apple-nonce:" + second);
    }

    @Test
    @DisplayName("한 번만 소모된다 — 읽는 호출이 곧 지우는 호출이다")
    void consumes_only_once() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("oauth:apple-nonce:n-1")).willReturn("1", (String) null);

        assertThat(store().consume("n-1")).isTrue();
        assertThat(store().consume("n-1")).isFalse();
    }

    @Test
    @DisplayName("모르는 nonce 는 false 다 — 만료 · 위조가 여기로 떨어진다")
    void unknown_nonce_is_false() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(null);

        assertThat(store().consume("gone")).isFalse();
    }

    @Test
    @DisplayName("빈 nonce 는 Redis 를 건드리지도 않는다")
    void blank_nonce_short_circuits() {
        assertThat(store().consume(null)).isFalse();
        assertThat(store().consume(" ")).isFalse();

        then(redisTemplate).should(never()).opsForValue();
    }
}
