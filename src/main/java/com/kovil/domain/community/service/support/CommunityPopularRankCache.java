package com.kovil.domain.community.service.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.Arrays;

/**
 * 인기 랭킹 캐시 — 순위(id 목록)는 필터 조합 키로 전역 캐시하고, 차단·비노출 같은
 * 사용자별 사정은 절대 캐시하지 않는다(적재 후 개인별로 거른다).
 *
 * <p>hit면 정렬 스캔 없이 즉시 응답, miss면 재집계 후 TTL로 저장 — 랭킹은 최대 TTL만큼
 * 낡을 수 있고 그것이 계약이다. Redis 장애는 fail-open: 캐시 없이 직접 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityPopularRankCache {

    static final String KEY_PREFIX = "community:popular:";
    private static final String DELIMITER = ",";
    private static final String EMPTY_MARKER = "-";

    private final StringRedisTemplate redisTemplate;
    private final CommunityPopularProperties properties;

    public List<Long> getOrCompute(String keySuffix, Supplier<List<Long>> loader) {
        if (properties.cacheTtl() == null || properties.cacheTtl().isZero()
                || properties.cacheTtl().isNegative()) {
            return loader.get(); // 캐시 끔(설정) — 테스트·디버깅용
        }
        String key = KEY_PREFIX + keySuffix;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return parse(cached);
            }
        } catch (DataAccessException redisDown) {
            log.warn("[community-popular] 캐시 조회 실패 — 직접 계산으로 진행(fail-open). cause={}",
                    redisDown.getMessage());
            return loader.get();
        }

        List<Long> ranked = loader.get();
        try {
            // 빈 결과도 캐시한다 — 글이 없는 초기 구간에 매 요청 스캔을 반복할 이유가 없다
            redisTemplate.opsForValue().set(key, join(ranked), properties.cacheTtl());
        } catch (DataAccessException ignored) {
            // 저장 실패는 다음 요청이 다시 시도한다 — 조회 응답을 막지 않는다
        }
        return ranked;
    }

    private String join(List<Long> ids) {
        return ids.isEmpty() ? EMPTY_MARKER
                : ids.stream().map(String::valueOf).collect(Collectors.joining(DELIMITER));
    }

    private List<Long> parse(String cached) {
        return EMPTY_MARKER.equals(cached) ? List.of()
                : Arrays.stream(cached.split(DELIMITER)).map(Long::valueOf).toList();
    }
}
