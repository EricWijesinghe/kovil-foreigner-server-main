package com.kovil.domain.community.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CommunityPopularRankCacheTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOperations;

    private CommunityPopularRankCache cache(Duration ttl) {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return new CommunityPopularRankCache(redisTemplate,
                new CommunityPopularProperties(4, 10, 1.5, ttl, 100));
    }

    @Test
    @DisplayName("hit면 로더를 부르지 않고 캐시 순서 그대로 돌려준다")
    void hit_skips_loader() {
        CommunityPopularRankCache rankCache = cache(Duration.ofHours(1));
        given(valueOperations.get("community:popular:WEEK:-:-")).willReturn("3,1,2");

        List<Long> ids = rankCache.getOrCompute("WEEK:-:-", () -> {
            throw new AssertionError("hit인데 로더가 불렸다");
        });

        assertThat(ids).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("miss면 재집계하고 TTL로 저장한다 — 빈 결과도 캐시한다")
    void miss_computes_and_stores() {
        CommunityPopularRankCache rankCache = cache(Duration.ofHours(1));
        given(valueOperations.get(anyString())).willReturn(null);

        assertThat(rankCache.getOrCompute("WEEK:-:-", () -> List.of(5L, 7L))).containsExactly(5L, 7L);
        then(valueOperations).should().set("community:popular:WEEK:-:-", "5,7", Duration.ofHours(1));

        assertThat(rankCache.getOrCompute("DAY:-:-", List::of)).isEmpty();
        then(valueOperations).should().set("community:popular:DAY:-:-", "-", Duration.ofHours(1));
    }

    @Test
    @DisplayName("빈 결과 캐시가 hit되면 로더 없이 빈 목록이다")
    void empty_marker_round_trips() {
        CommunityPopularRankCache rankCache = cache(Duration.ofHours(1));
        given(valueOperations.get("community:popular:DAY:-:-")).willReturn("-");

        assertThat(rankCache.getOrCompute("DAY:-:-", () -> {
            throw new AssertionError("hit인데 로더가 불렸다");
        })).isEmpty();
    }

    // 랭킹은 편의 기능이다 — Redis가 죽었다고 피드가 죽으면 안 된다.
    @Test
    @DisplayName("Redis 장애는 fail-open — 캐시 없이 직접 계산한다")
    void redis_down_fails_open() {
        CommunityPopularRankCache rankCache = cache(Duration.ofHours(1));
        given(valueOperations.get(anyString())).willThrow(new QueryTimeoutException("down"));

        assertThat(rankCache.getOrCompute("WEEK:-:-", () -> List.of(9L))).containsExactly(9L);
        then(valueOperations).should().get(anyString());
        then(valueOperations).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("TTL이 0이면 캐시를 끄고 항상 직접 계산한다")
    void zero_ttl_disables_cache() {
        CommunityPopularRankCache rankCache = cache(Duration.ZERO);

        assertThat(rankCache.getOrCompute("WEEK:-:-", () -> List.of(1L))).containsExactly(1L);
        then(valueOperations).should(Mockito.never()).get(any());
        then(valueOperations).should(Mockito.never()).set(any(), any(), any(Duration.class));
    }
}
