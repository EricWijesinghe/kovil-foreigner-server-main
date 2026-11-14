package com.kovil.infra.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// 액세스 토큰은 무상태라 서버가 취소할 방법이 없다. 취소해야 하는 경우가 둘이라 축도 둘이다.
//
//  · jti 축 — 로그아웃. 그 토큰 하나만 죽인다.
//  · 유저 축 — 역할 변경·정지처럼 **그 사람의 토큰을 전부** 죽여야 하는 경우.
//    발급한 액세스 토큰을 추적하지 않으므로 jti 를 모아 지울 수 없다. 대신 "이 시각 이전
//    발급은 전부 무효"라는 기준선 하나를 두면 O(1) 로 같은 일을 한다(PO 확정 2026-09-17).
//
// ★ 둘 다 액세스 토큰 수명만큼만 산다 — 그 뒤에는 어차피 만료라 Redis 가 알아서 비운다.
@Slf4j
@Component
@RequiredArgsConstructor
public class BlacklistedTokenStore {

    private static final String PREFIX = "blacklist:jti:";
    private static final String USER_PREFIX = "blacklist:user:";

    private final StringRedisTemplate redisTemplate;

    public void add(String jti, Duration ttl) {
        if (jti == null || jti.isBlank() || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(PREFIX + jti, "1", ttl);
    }

    /**
     * 이 토큰을 받아들여도 되는가 — 두 축을 <b>한 번의 왕복</b>으로 묻는다.
     *
     * <p>축이 둘인 이유: 로그아웃은 "그 토큰 하나"를 죽이고(다른 기기는 살아 있어야 한다),
     * 역할 변경·도난 대응은 "그 사람 전부"를 죽인다. 성격이 달라 한 키로 합칠 수 없다.
     * 합칠 수 있는 것은 키가 아니라 <b>왕복</b>이라, MGET 으로 두 키를 한 번에 읽는다
     * (인증된 모든 요청이 지나는 자리다 — 왕복 하나가 곧 전 API 지연이다).
     *
     * <p>★ 전제: Redis 가 <b>클러스터 모드가 아니다</b>(운영은 ElastiCache Valkey 단일 노드 · 샤드 1).
     * 클러스터 모드 엔드포인트에 단일 노드 설정(spring.data.redis.host)으로 붙으면 두 키가 다른 슬롯이라
     * 이 MGET 이 CROSSSLOT 으로 실패하고, 아래 catch 가 그것도 장애로 보고 <b>통과시킨다</b> —
     * ERROR 는 요청마다 남지만 로그아웃·역할 변경·정지 취소가 전부 멈춘다(샤드가 하나면 쓰기는
     * 성공해 보여서 더 늦게 안다). 클러스터로 옮길 때는 spring.data.redis.cluster.nodes 로 붙이고,
     * 왕복 하나를 지키려면 두 키에 같은 해시태그(`blacklist:{uuid}:jti:…`, `blacklist:{uuid}:user`)를 붙인다.
     */
    public Rejection check(String jti, String userUuid, Instant issuedAt) {
        if (jti == null && userUuid == null) {
            return Rejection.NONE;
        }
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(List.of(
                    PREFIX + (jti == null ? "" : jti),
                    USER_PREFIX + (userUuid == null ? "" : userUuid)));
        } catch (DataAccessException e) {
            // ★ Redis 가 죽으면 **통과시킨다**(PO 확정 2026-09-17).
            //
            //   1차 인증인 서명 검증은 Redis 없이 된다 — 취소는 그 위에 얹은 보강이다.
            //   막으면(fail-closed) Redis 장애가 곧 전 서비스 로그인 불가인데, 대가로 얻는 것은
            //   "장애 중에 하필 취소 대상이 들어오는" 훨씬 드문 경우를 막는 것뿐이다.
            //   되는 대신 **조용히 되지는 않는다** — 이 ERROR 가 취소가 멈춘 유일한 신호다.
            log.error("[blacklist] Redis 조회 실패 — 토큰 취소 검사를 건너뛴다(fail-open)", e);
            return Rejection.NONE;
        }
        if (values == null) {
            return Rejection.NONE;
        }

        if (jti != null && values.get(0) != null) {
            return Rejection.BLACKLISTED_TOKEN;
        }
        return checkUserCutoff(values.get(1), issuedAt);
    }

    private Rejection checkUserCutoff(String cutoff, Instant issuedAt) {
        if (cutoff == null || issuedAt == null) {
            return Rejection.NONE;
        }
        long cutoffSeconds;
        try {
            cutoffSeconds = Long.parseLong(cutoff);
        } catch (NumberFormatException e) {
            // 우리가 쓴 값이 아니다 — 같은 Redis 를 쓰는 다른 제품과 키가 겹쳤을 때 여기로 온다.
            // 던지면 그 사용자의 모든 요청이 500 이 된다. 통과시키되 시끄럽게 남긴다.
            log.error("[blacklist] 취소선 값이 숫자가 아니다 — 키 충돌 의심. value={}", cutoff);
            return Rejection.NONE;
        }
        // 초 단위로 비교하되 **기준선과 같은 초도 무효**로 본다(`<=`). JWT 의 iat 는 초 정밀도라,
        // 취소와 같은 초에 발급된 토큰을 살려 두면 그 한 장이 빠져나간다.
        return issuedAt.getEpochSecond() <= cutoffSeconds ? Rejection.REVOKED_USER : Rejection.NONE;
    }

    /** 거절 사유 — 로그에 무엇 때문인지 남겨야 원인을 찾을 수 있다. */
    public enum Rejection {
        NONE, BLACKLISTED_TOKEN, REVOKED_USER
    }

    /**
     * 이 사용자의 <b>지금까지 발급된 액세스 토큰을 전부</b> 무효화한다.
     *
     * <p>역할이 바뀌면 리프레시만 끊어서는 부족하다 — 액세스 토큰은 발급 시점의 role 을
     * 그대로 들고 있어, 강등된 관리자가 토큰 수명(기본 30분) 동안 옛 권한을 계속 쓴다.
     *
     * @param ttl 액세스 토큰 수명. 그만큼만 기억하면 된다 — 그 뒤 토큰은 스스로 만료된다.
     */
    public void revokeAllBefore(String userUuid, Instant cutoff, Duration ttl) {
        if (userUuid == null || userUuid.isBlank() || cutoff == null
                || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue()
                .set(USER_PREFIX + userUuid, Long.toString(cutoff.getEpochSecond()), ttl);
    }

}
