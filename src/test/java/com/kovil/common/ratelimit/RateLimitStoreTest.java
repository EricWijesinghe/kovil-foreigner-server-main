package com.kovil.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RateLimitStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RateLimitStore store() {
        return new RateLimitStore(redisTemplate, RateLimitTestFixtures.enabledProperties());
    }

    private RateLimitStore disabledStore() {
        return new RateLimitStore(redisTemplate, new RateLimitProperties(false, null, null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenScriptReturns(List<Long> result) {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .willReturn(result);
    }

    @Nested
    @DisplayName("tryConsume()")
    class TryConsume {

        @Test
        @DisplayName("한도 내면 허용하고 정책·키가 조합된 Redis 키로 스크립트를 실행한다")
        void allowed() {
            givenScriptReturns(List.of(1L, 55L));

            RateLimitStore.RateLimitResult result = store().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7");

            assertThat(result.allowed()).isTrue();

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
            then(redisTemplate).should()
                    .execute(any(RedisScript.class), keysCaptor.capture(), anyString(), anyString());
            assertThat(keysCaptor.getValue()).containsExactly("ratelimit:signup:203.0.113.7");
        }

        @Test
        @DisplayName("한도 초과면 거절과 함께 윈도우 잔여 초를 반환한다")
        void denied_withRetryAfter() {
            givenScriptReturns(List.of(0L, 42L));

            RateLimitStore.RateLimitResult result = store().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7");

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(42L);
        }

        // 리미터가 죽었다고 서비스가 잠기면 안 된다 — Redis 장애의 비용이 전면 장애가 되어버린다.
        @Test
        @DisplayName("Redis 장애면 fail-open — 허용한다")
        void redisDown_failOpen() {
            given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                    .willThrow(new QueryTimeoutException("redis down"));

            assertThat(store().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7").allowed()).isTrue();
        }

        @Test
        @DisplayName("enabled=false면 Redis를 건드리지 않고 허용한다(비상 스위치)")
        void disabled_bypasses() {
            assertThat(disabledStore().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7").allowed()).isTrue();
            then(redisTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Lua 응답이 무효(null·원소 부족)면 fail-open")
        void invalidScriptResponse_failOpen() {
            givenScriptReturns(null);
            assertThat(store().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7").allowed()).isTrue();

            givenScriptReturns(List.of(1L));
            assertThat(store().tryConsume(RateLimitPolicy.SIGNUP, "203.0.113.7").allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("peek()")
    class Peek {

        @Test
        @DisplayName("키가 없으면 허용 — 카운터를 소비하지 않는다")
        void noKey_allowed() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("ratelimit:signin_fail_id:someone")).willReturn(null);

            assertThat(store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone").allowed()).isTrue();
        }

        @Test
        @DisplayName("누적이 한도 이상이면 거절과 잔여 TTL을 반환한다")
        void exceeded_denied() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("ratelimit:signin_fail_id:someone")).willReturn("10");
            given(redisTemplate.getExpire("ratelimit:signin_fail_id:someone")).willReturn(120L);

            RateLimitStore.RateLimitResult result = store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone");

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(120L);
        }

        @Test
        @DisplayName("누적이 한도 미만이면 허용한다(경계: limit-1)")
        void belowLimit_allowed() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("ratelimit:signin_fail_id:someone")).willReturn("9");

            assertThat(store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone").allowed()).isTrue();
        }

        @Test
        @DisplayName("Redis 장애·값 오염이면 fail-open")
        void redisDownOrCorrupted_failOpen() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString()))
                    .willThrow(new QueryTimeoutException("redis down"));
            assertThat(store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone").allowed()).isTrue();
        }

        @Test
        @DisplayName("저장값이 숫자가 아니면 fail-open")
        void nonNumericValue_failOpen() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn("corrupted");

            assertThat(store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone").allowed()).isTrue();
        }

        @Test
        @DisplayName("초과인데 TTL이 유실되면 retryAfter는 윈도우 전체로 폴백한다")
        void exceededWithLostTtl_fallsBackToWindow() {
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("ratelimit:signin_fail_id:someone")).willReturn("10");
            given(redisTemplate.getExpire("ratelimit:signin_fail_id:someone")).willReturn(-1L);

            RateLimitStore.RateLimitResult result = store().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone");

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(60L);
        }

        @Test
        @DisplayName("enabled=false면 Redis를 건드리지 않고 허용한다")
        void disabled_bypasses() {
            assertThat(disabledStore().peek(RateLimitPolicy.SIGNIN_FAIL_ID, "someone").allowed()).isTrue();
            then(redisTemplate).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("record()")
    class Record {

        @Test
        @DisplayName("판정 없이 카운터만 올리고 Redis 장애도 삼킨다")
        void records_andSwallowsErrors() {
            givenScriptReturns(List.of(1L, 300L));
            store().record(RateLimitPolicy.SIGNIN_FAIL_IP, "203.0.113.7");
            then(redisTemplate).should()
                    .execute(any(RedisScript.class), anyList(), anyString(), anyString());

            given(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                    .willThrow(new QueryTimeoutException("redis down"));
            store().record(RateLimitPolicy.SIGNIN_FAIL_IP, "203.0.113.7");
        }

        @Test
        @DisplayName("enabled=false면 기록도 하지 않는다")
        void disabled_skipsRecording() {
            disabledStore().record(RateLimitPolicy.SIGNIN_FAIL_IP, "203.0.113.7");
            then(redisTemplate).shouldHaveNoInteractions();
        }
    }

    @Test
    @DisplayName("limitOf는 설정된 한도를 돌려준다 — 안내 문구와 실제 한도의 드리프트 방지")
    void limitOf_returnsConfiguredLimit() {
        assertThat(store().limitOf(RateLimitPolicy.PHONE_VERIFICATION_SEND)).isEqualTo(10);
    }
}
