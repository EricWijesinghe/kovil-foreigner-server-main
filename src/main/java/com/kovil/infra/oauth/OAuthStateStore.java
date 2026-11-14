package com.kovil.infra.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// CSRF 방어용 state. 한 번 쓰면 지운다 — 재사용되면 방어가 성립하지 않는다.
//
// 값 자리에는 **그 로그인 시도의 PKCE code_challenge** 를 담는다. state 는 브라우저를 지나가므로
// 가로챌 수 있지만 challenge 에 대응하는 verifier 는 앱 밖으로 나가지 않는다 —
// 둘을 여기서 묶어 두어야 가로챈 code·state 가 쓸모없어진다(PkceValidator 주석 참조).
//
// 돌아갈 곳(앱 · 웹)도 같은 값에 붙여 둔다. 웹이면 "WEB:" 을 앞에 붙이고, 앱은 challenge 만 둔다.
// challenge(base64url)에는 ':' 이 없어 둘이 섞이지 않고, 이 규칙 전에 저장된 값은 앱으로 읽힌다.
@Component
@RequiredArgsConstructor
public class OAuthStateStore {

    private static final String PREFIX = "oauth:state:";
    private static final String WEB_MARK = OAuthReturnTarget.WEB.name() + ":";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public String generate(String codeChallenge, OAuthReturnTarget target) {
        String state = UUID.randomUUID().toString();
        String value = target == OAuthReturnTarget.WEB ? WEB_MARK + codeChallenge : codeChallenge;
        redisTemplate.opsForValue().set(PREFIX + state, value, TTL);
        return state;
    }

    /**
     * state 를 소모하고 묶여 있던 code_challenge 를 돌려준다. 만료·재사용·위조는 전부 null 이다.
     *
     * 검증 성공 여부와 무관하게 **읽는 순간 지운다.** 실패해도 남겨 두면 같은 challenge 를 상대로
     * verifier 를 반복 대입할 창이 열린다.
     */
    public String consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = redisTemplate.opsForValue().getAndDelete(PREFIX + state);
        return value == null ? null : challengeOf(value);
    }

    // 되돌림 페이지가 쓴다. 소모하지 않는다. 소모는 뒤이은 code 교환의 몫이다. 모르는 state 는 null 이다.
    public OAuthReturnTarget returnTargetOf(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(PREFIX + state);
        if (value == null) {
            return null;
        }
        return value.startsWith(WEB_MARK) ? OAuthReturnTarget.WEB : OAuthReturnTarget.APP;
    }

    private static String challengeOf(String value) {
        return value.startsWith(WEB_MARK) ? value.substring(WEB_MARK.length()) : value;
    }
}
