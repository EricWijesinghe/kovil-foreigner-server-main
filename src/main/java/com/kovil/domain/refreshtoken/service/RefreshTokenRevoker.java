package com.kovil.domain.refreshtoken.service;

import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.refreshtoken.repository.RefreshTokenRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// ★ 도난 대응은 호출자의 트랜잭션과 운명을 같이하면 안 된다.
//
// 재사용을 탐지하면 세션을 끊고 곧바로 예외를 던지는데, 같은 트랜잭션에 있으면
// 그 예외가 폐기까지 되돌린다. 실측에서 확인했다 — 경고 로그만 남고 DB는 그대로였다.
// 별도 빈으로 뺀 이유는 자기호출(self-invocation)이 프록시를 우회해
// REQUIRES_NEW가 걸리지 않기 때문이다.
@Component
@RequiredArgsConstructor
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final BlacklistedTokenStore blacklistedTokenStore;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    /**
     * 도난 대응 — 리프레시와 <b>살아 있는 액세스 토큰까지</b> 함께 끊는다.
     *
     * <p>★ 액세스 토큰을 남기면 <b>피해자만 쫓겨나고 탈취자는 남는다</b>: 피해자는 리프레시가
     * 끊겨 로그아웃되지만, 탈취자가 이미 받아 둔 액세스 토큰은 수명(기본 30분)만큼 그대로
     * 통한다(적대적 리뷰 2026-09-17 2라운드). Redis 는 트랜잭션 밖이라 이 취소선은
     * 뒤따르는 예외에도 되돌아가지 않는다 — 도난 대응에서는 그게 맞다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllFor(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        refreshTokenRepository.findByUserIdAndStatusForUpdate(userId, RefreshTokenStatus.ACTIVE)
                .forEach(token -> token.revoke(now));
        // 잠금 조회 — 발급 경로가 이 행에서 기다렸다가 새 상태를 읽게 한다(RefreshTokenService 참조).
        userRepository.findAuthSnapshotForUpdate(userId).ifPresent(snapshot ->
                blacklistedTokenStore.revokeAllBefore(
                        snapshot.getUuid(), clock.instant(), jwtProperties.accessTokenTtl()));
    }

    /*
     * 회전 직후 옛 토큰이 다시 왔을 때(유예 창 안) — 그 토큰에서 나온 자식이 쓰이지 않았으면(회전되지 않음) true.
     * 살아 있으면 자식을 끊고, 이미 끊겨 있으면(앞선 유예 처리 · 세션 상한 · 로그아웃) 그대로 둔다.
     * 자식이 회전됐거나(쓰임) 없으면 false 이고 호출부가 전부 끊는다.
     * ★ "쓰임"은 ROTATED 하나다. REVOKED 자식까지 쓰임으로 보면, 응답이 연달아 두 번 사라진 기기가 세 번째로 보낼 때
     *   다른 기기까지 다 끊긴다 — 이 창이 막으려던 일이다. 이 분기는 새 쌍을 주지 않으므로 탈취자가 얻는 것은 없다.
     * ★ 자식 행을 잠그고 판정한다 — 판정과 폐기 사이에 자식이 회전되면 탈취 신호를 놓친다.
     * ★ 별도 트랜잭션인 이유는 revokeAllFor 와 같다(뒤따르는 예외가 폐기를 되돌림). 바깥이 잠근 행은 부모라 서로 막지 않는다.
     * ★ 액세스 토큰은 끊지 않는다 — 사용자 단위 취소선이라 다른 기기까지 끊긴다. 그 자식과 함께 나간 액세스 토큰은 수명만큼 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean revokeUnusedChildOf(Long parentId) {
        List<RefreshToken> children = refreshTokenRepository.findChildrenForUpdate(parentId);
        if (children.size() != 1 || children.get(0).getStatus() == RefreshTokenStatus.ROTATED) {
            return false;
        }
        RefreshToken child = children.get(0);
        if (child.isActive()) {
            child.revoke(LocalDateTime.now(clock));
        }
        return true;
    }

    // EXPIRED 마킹도 같은 운명 분리가 필요하다(AUDIT_2026_08_26) — rotate는 만료를 발견하면
    // 곧바로 예외를 던지는데, 같은 트랜잭션의 더티체킹은 그 예외에 항상 롤백되어
    // EXPIRED가 DB에 도달한 적이 없었다(상태가 영원히 ACTIVE로 남는 죽은 코드였다).
    // ★ 조건부(WHERE status=ACTIVE)여야 한다 — 읽고-쓰기는 그 사이 커밋된 REVOKED(도난 대응)를
    //   EXPIRED 로 덮어써 재사용 탐지 신호를 지운다(적대적 리뷰 2026-09-02). 0행 갱신은 정상이다:
    //   경합 상대가 이미 폐기했다는 뜻이고, 만료든 폐기든 rotate 는 어차피 거절한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Long tokenId) {
        refreshTokenRepository.expireIfActive(tokenId, LocalDateTime.now(clock));
    }
}
