package com.kovil.infra.oauth.apple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

// ★ Apple 웹은 이름을 id_token 에 넣지 않고, 처음 인가할 때 한 번만 form_post 의 user 로 준다.
//   그 POST 는 서버 콜백이 받으므로 앱 · 웹은 user 원문을 볼 수 없다. 콜백에서 이름만 꺼내 state 에 묶어 두고,
//   뒤이은 code 교환이 꺼내 쓴다. 이메일은 id_token 에 있어 여기 두지 않는다.
//   state 와 같은 10분이고, 먼저 온 값만 남긴다(뒤에 같은 state 로 덮어쓰지 못하게).
@Slf4j
@Component
@RequiredArgsConstructor
public class AppleCallbackNameStore {

    private static final String PREFIX = "oauth:apple-name:";
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int NAME_MAX_LENGTH = 100;

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public void save(String state, String userPayload) {
        String name = parseName(userPayload);
        if (state == null || state.isBlank() || name == null) {
            return;
        }
        redisTemplate.opsForValue().setIfAbsent(PREFIX + state, name, TTL);
    }

    public String consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().getAndDelete(PREFIX + state);
    }

    // {"name":{"firstName":"Van A","lastName":"NGUYEN"},"email":"..."} — 이름이 없을 수 있고,
    // 깨진 값이 와도 로그인 자체를 실패시키지 않는다(이름은 부가 정보다).
    String parseName(String userPayload) {
        if (userPayload == null || userPayload.isBlank()) {
            return null;
        }
        try {
            JsonNode name = jsonMapper.readTree(userPayload).path("name");
            String first = name.path("firstName").asString("");
            String last = name.path("lastName").asString("");
            String joined = (first + " " + last).trim().replaceAll("\\s+", " ");
            if (joined.isEmpty()) {
                return null;
            }
            // users.name 이 100자다. 넘는 값을 그대로 두면 가입이 DB 에서 터진다.
            if (joined.codePointCount(0, joined.length()) <= NAME_MAX_LENGTH) {
                return joined;
            }
            return joined.substring(0, joined.offsetByCodePoints(0, NAME_MAX_LENGTH)).trim();
        } catch (Exception e) {
            log.warn("[oauth] Apple user 페이로드를 읽지 못했다. 이름 없이 진행한다");
            return null;
        }
    }
}
