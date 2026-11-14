package com.kovil.infra.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

/*
 * ★ 인증된 **모든** 요청이 이 판정을 지난다. 그런데 필터 슬라이스 테스트는 이 스토어를
 *   목으로 대체하므로 **여기 로직은 한 줄도 실행되지 않았다** — 실제로 그랬다:
 *   유저 축 비교를 통째로 지워도 아무 테스트도 죽지 않았다(적대적 리뷰 2026-09-17).
 *   관리자 수 쿼리와 같은 구멍이라 같은 방식으로 막는다.
 */
@ExtendWith(MockitoExtension.class)
class BlacklistedTokenStoreTest {

    private static final String JTI_KEY = "blacklist:jti:tok-1";
    private static final String USER_KEY = "blacklist:user:u-1";
    private static final Instant CUTOFF = Instant.parse("2026-09-17T10:00:00Z");

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    @InjectMocks BlacklistedTokenStore store;

    private void redisReturns(String jtiValue, String cutoffValue) {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.multiGet(List.of(JTI_KEY, USER_KEY)))
                .willReturn(Arrays.asList(jtiValue, cutoffValue));
    }

    private BlacklistedTokenStore.Rejection check(Instant issuedAt) {
        return store.check("tok-1", "u-1", issuedAt);
    }

    /*
     * ★ 쓰기와 읽기가 **같은 단위**여야 한다. revokeAllBefore 는 epoch **초**로 쓰고 check 는
     *   초로 읽는데, 한쪽만 밀리초로 바뀌면 취소선이 약 1000배 미래로 가서 재로그인해도
     *   토큰 수명 내내 401 이 된다(반대로 바뀌면 취소가 통째로 무력화된다). 두 쪽을 따로
     *   고정하면 그 어긋남이 둘 다 통과한다 — 그래서 한 테스트 안에서 왕복시킨다
     *   (적대적 리뷰 2026-09-17 3라운드).
     */
    @Test
    @DisplayName("취소선은 쓴 값 그대로 읽힌다 — 같은 초 발급은 막고 다음 초는 통과한다")
    void cutoff_write_and_read_agree() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store.revokeAllBefore("u-1", CUTOFF, Duration.ofMinutes(30));

        ArgumentCaptor<String> written = ArgumentCaptor.forClass(String.class);
        then(valueOperations).should().set(
                ArgumentMatchers.eq(USER_KEY), written.capture(),
                ArgumentMatchers.eq(Duration.ofMinutes(30)));

        // 쓴 값을 그대로 되먹인다 — 단위가 어긋나면 아래 두 단언 중 하나가 죽는다.
        given(valueOperations.multiGet(List.of(JTI_KEY, USER_KEY)))
                .willReturn(Arrays.asList(null, written.getValue()));

        assertThat(check(CUTOFF)).isEqualTo(BlacklistedTokenStore.Rejection.REVOKED_USER);
        assertThat(check(CUTOFF.plusSeconds(1))).isEqualTo(BlacklistedTokenStore.Rejection.NONE);
    }

    // 로그아웃 축의 **쓰기**. 읽는 쪽과 키 문자열이 어긋나면 로그아웃이 조용히 무력화되는데,
    // 읽기만 고정하면 그 어긋남이 통과한다 — 같은 상수로 양쪽을 묶는다.
    @Test
    @DisplayName("로그아웃은 jti 키에 TTL 을 붙여 적는다")
    void add_writes_jti_key_with_ttl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        store.add("tok-1", Duration.ofMinutes(5));

        then(valueOperations).should().set(JTI_KEY, "1", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Redis 가 죽어도 요청은 통과한다 — 취소만 멈추고 인증 전체가 멈추지는 않는다")
    void redis_outage_fails_open() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.multiGet(List.of(JTI_KEY, USER_KEY)))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThat(check(CUTOFF)).isEqualTo(BlacklistedTokenStore.Rejection.NONE);
    }

    // 같은 Redis 를 다른 제품과 공유하면 키가 겹칠 수 있다(운영 QA 가 실제로 공유한다).
    // 파싱이 터지면 그 사용자의 **모든** 요청이 500 이 된다.
    @Test
    @DisplayName("취소선 값이 숫자가 아니어도 500 이 아니라 통과다")
    void garbage_cutoff_does_not_explode() {
        redisReturns(null, "not-a-number");

        assertThat(check(CUTOFF)).isEqualTo(BlacklistedTokenStore.Rejection.NONE);
    }

    @Test
    @DisplayName("로그아웃한 토큰은 거절된다")
    void blacklisted_jti_is_rejected() {
        redisReturns("1", null);
        assertThat(check(CUTOFF.plusSeconds(60)))
                .isEqualTo(BlacklistedTokenStore.Rejection.BLACKLISTED_TOKEN);
    }

    @Test
    @DisplayName("취소선보다 먼저 발급된 토큰은 거절된다")
    void token_issued_before_cutoff_is_rejected() {
        redisReturns(null, Long.toString(CUTOFF.getEpochSecond()));
        assertThat(check(CUTOFF.minusSeconds(1)))
                .isEqualTo(BlacklistedTokenStore.Rejection.REVOKED_USER);
    }

    /*
     * ★ JWT 의 iat 는 **초 정밀도**다. 취소와 같은 초에 발급된 토큰을 살려 두면
     *   그 한 장이 창을 빠져나간다 — 강등 직후 발급된 토큰이 정확히 그 경우다.
     */
    @Test
    @DisplayName("취소선과 같은 초에 발급된 토큰도 거절된다")
    void token_issued_in_the_same_second_is_rejected() {
        redisReturns(null, Long.toString(CUTOFF.getEpochSecond()));
        assertThat(check(CUTOFF))
                .isEqualTo(BlacklistedTokenStore.Rejection.REVOKED_USER);
    }

    @Test
    @DisplayName("취소선 이후 발급된 토큰은 통과한다 — 다시 로그인한 사람을 막으면 안 된다")
    void token_issued_after_cutoff_passes() {
        redisReturns(null, Long.toString(CUTOFF.getEpochSecond()));
        assertThat(check(CUTOFF.plusSeconds(1)))
                .isEqualTo(BlacklistedTokenStore.Rejection.NONE);
    }

    @Test
    @DisplayName("아무 취소도 없으면 통과한다")
    void clean_token_passes() {
        redisReturns(null, null);
        assertThat(check(CUTOFF)).isEqualTo(BlacklistedTokenStore.Rejection.NONE);
    }

    /*
     * ★ 이 변경의 요지. 두 축을 따로 물으면 요청마다 Redis 를 두 번 간다 —
     *   인증된 모든 요청이 지나는 자리라 왕복 하나가 곧 전 API 지연이다(PO 지시 2026-09-17).
     */
    @Test
    @DisplayName("두 축을 한 번의 왕복으로 묻는다")
    void asks_redis_once() {
        redisReturns(null, null);

        check(CUTOFF);

        then(valueOperations).should(times(1)).multiGet(List.of(JTI_KEY, USER_KEY));
        then(valueOperations).shouldHaveNoMoreInteractions();
    }
}
