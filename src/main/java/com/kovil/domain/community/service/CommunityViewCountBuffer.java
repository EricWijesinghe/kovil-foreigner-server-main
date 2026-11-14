package com.kovil.domain.community.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

// INV-11 — 조회수 증가는 여기(Redis INCR)로만 온다. 인기 글 한 행에 UPDATE가 몰리면
// 쓰기 락에 커넥션 풀이 고갈된다. 배치가 delta를 모아 가산한다(최대 1분 지연 허용).
@Component
@RequiredArgsConstructor
public class CommunityViewCountBuffer {

    private static final String KEY_PREFIX = "community:view:";

    private final StringRedisTemplate redisTemplate;

    public void increase(Long postId) {
        redisTemplate.opsForValue().increment(KEY_PREFIX + postId);
    }

    public Map<Long, Long> drain() {
        Map<Long, Long> deltas = new LinkedHashMap<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redisTemplate.opsForValue().getAndDelete(key);
                if (value == null) {
                    continue;
                }
                deltas.put(Long.parseLong(key.substring(KEY_PREFIX.length())), Long.parseLong(value));
            }
        }
        return deltas;
    }

    // DB 가산이 실패한 delta를 되돌려 둔다 — 버리면 그만큼 조회수가 사라진다.
    public void restore(Long postId, long delta) {
        redisTemplate.opsForValue().increment(KEY_PREFIX + postId, delta);
    }
}
