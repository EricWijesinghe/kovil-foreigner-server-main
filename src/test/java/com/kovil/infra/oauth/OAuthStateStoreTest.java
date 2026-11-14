package com.kovil.infra.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// state 와 code_challenge 가 한 몸이라는 것을 고정한다 — 둘이 갈리면 PKCE 검증이 무력해진다.
@ExtendWith(MockitoExtension.class)
class OAuthStateStoreTest {

    private static final String CHALLENGE = "EI7uJUdGyTH_Hx7H28FU3awPzco6gxxv10wChDLeuPg";

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private OAuthStateStore store() {
        return new OAuthStateStore(redisTemplate);
    }

    @Test
    @DisplayName("발급한 state 의 값 자리에 code_challenge 를 담는다")
    void stores_challenge_under_state() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        String state = store().generate(CHALLENGE, OAuthReturnTarget.APP);

        assertThat(state).isNotBlank();
        then(valueOperations).should().set("oauth:state:" + state, CHALLENGE, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("웹으로 돌아갈 시도는 같은 값에 목적지를 붙이고, 소모하면 challenge 만 돌려준다")
    void web_target_rides_on_the_same_value() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        String state = store().generate(CHALLENGE, OAuthReturnTarget.WEB);

        then(valueOperations).should().set("oauth:state:" + state, "WEB:" + CHALLENGE, Duration.ofMinutes(10));
        given(valueOperations.getAndDelete("oauth:state:" + state)).willReturn("WEB:" + CHALLENGE);
        assertThat(store().consume(state)).isEqualTo(CHALLENGE);
    }

    // 되돌림 페이지는 목적지만 본다. 여기서 지우면 뒤이은 code 교환이 늘 OAUTH_STATE_INVALID 다.
    @Test
    @DisplayName("목적지 조회는 state 를 소모하지 않는다")
    void return_target_lookup_does_not_consume() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:state:s-web")).willReturn("WEB:" + CHALLENGE);
        given(valueOperations.get("oauth:state:s-app")).willReturn(CHALLENGE);

        assertThat(store().returnTargetOf("s-web")).isEqualTo(OAuthReturnTarget.WEB);
        assertThat(store().returnTargetOf("s-app")).isEqualTo(OAuthReturnTarget.APP);
        assertThat(store().returnTargetOf("gone")).isNull();
        then(valueOperations).should(never()).getAndDelete(anyString());
    }

    // 배포 순간 남아 있던 state(앞의 규칙으로 저장된 값)는 앱으로 돌아가야 한다.
    @Test
    @DisplayName("목적지 표시가 없는 옛 값은 앱이고 challenge 가 그대로 나온다")
    void legacy_value_is_app() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("oauth:state:old")).willReturn(CHALLENGE);
        given(valueOperations.getAndDelete("oauth:state:old")).willReturn(CHALLENGE);

        assertThat(store().returnTargetOf("old")).isEqualTo(OAuthReturnTarget.APP);
        assertThat(store().consume("old")).isEqualTo(CHALLENGE);
    }

    // 읽는 순간 지운다. 남겨 두면 같은 challenge 를 상대로 verifier 를 반복 대입할 수 있다.
    @Test
    @DisplayName("소모하면 challenge 를 돌려주고 같은 호출로 지운다")
    void consume_returns_challenge_and_deletes() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("oauth:state:s-1")).willReturn(CHALLENGE);

        assertThat(store().consume("s-1")).isEqualTo(CHALLENGE);
    }

    @Test
    @DisplayName("모르는 state 는 null 이다 — 만료·재사용·위조가 여기로 떨어진다")
    void unknown_state_is_null() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willReturn(null);

        assertThat(store().consume("gone")).isNull();
    }

    @Test
    @DisplayName("빈 state 는 Redis 를 건드리지도 않는다")
    void blank_state_short_circuits() {
        assertThat(store().consume(null)).isNull();
        assertThat(store().consume("  ")).isNull();
        assertThat(store().returnTargetOf(null)).isNull();
        assertThat(store().returnTargetOf(" ")).isNull();

        then(redisTemplate).should(never()).opsForValue();
    }
}
