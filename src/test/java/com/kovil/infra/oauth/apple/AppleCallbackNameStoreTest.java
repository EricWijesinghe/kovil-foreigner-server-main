package com.kovil.infra.oauth.apple;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// Apple 웹의 이름은 처음 인가 때 form_post 로 한 번만 온다. 여기서 흘리면 그 계정의 이름은 다시 받을 수 없다.
@ExtendWith(MockitoExtension.class)
class AppleCallbackNameStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private AppleCallbackNameStore store() {
        return new AppleCallbackNameStore(redisTemplate, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("이름만 꺼내 state 에 10분 묶고, 먼저 온 값만 남긴다 · 이메일은 두지 않는다")
    void saves_only_the_name_first_write_wins() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store().save("state-1",
                "{\"name\":{\"firstName\":\"Van  A\",\"lastName\":\"NGUYEN\"},\"email\":\"x@privaterelay.appleid.com\"}");

        then(valueOperations).should().setIfAbsent("oauth:apple-name:state-1", "Van A NGUYEN", Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("이름이 없거나 깨진 값이면 아무것도 두지 않는다")
    void nothing_to_save() {
        store().save("state-1", null);
        store().save("state-1", "{\"email\":\"a@b.com\"}");
        store().save("state-1", "{broken");
        store().save(" ", "{\"name\":{\"firstName\":\"Van\"}}");

        then(redisTemplate).should(never()).opsForValue();
    }

    @Test
    @DisplayName("꺼내면 지운다")
    void consume_gets_and_deletes() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete("oauth:apple-name:state-1")).willReturn("Van A");

        assertThat(store().consume("state-1")).isEqualTo("Van A");
        assertThat(store().consume(null)).isNull();
    }

    @Test
    @DisplayName("users.name(100자)을 넘는 이름은 자른다")
    void long_name_is_capped() {
        String payload = "{\"name\":{\"firstName\":\"" + "가".repeat(60) + "\",\"lastName\":\"" + "나".repeat(60) + "\"}}";

        assertThat(store().parseName(payload)).hasSize(100);
    }
}
