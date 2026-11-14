package com.kovil.domain.refreshtoken.service;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.refreshtoken.RefreshTokenProperties;
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.entity.RefreshToken;
import com.kovil.domain.refreshtoken.enums.RefreshTokenStatus;
import com.kovil.domain.refreshtoken.exception.RefreshTokenException;
import com.kovil.domain.refreshtoken.repository.RefreshTokenRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.infra.crypto.Hasher;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static com.kovil.domain.refreshtoken.exception.RefreshTokenErrorCode.REFRESH_TOKEN_INVALID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTE_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final RefreshTokenProperties properties;
    private final Hasher hasher;
    private final UserRepository userRepository;
    private final BlacklistedTokenStore blacklistedTokenStore;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final PartnerDivisionRepository partnerDivisionRepository;

    @Transactional
    public RefreshTokenIssueResult issue(User user) {
        return doIssue(user, null);
    }

    @Transactional
    public RefreshTokenIssueResult rotate(String rawToken) {
        LocalDateTime now = LocalDateTime.now(clock);
        String tokenHash = hasher.hash(rawToken);

        // ★ 만료 검사는 잠금 **이전**에, 엔티티가 아닌 프로젝션으로 한다(적대적 리뷰 2026-09-02).
        //   잠금을 쥔 채 REQUIRES_NEW 로 같은 행을 EXPIRED 마킹하면 새 트랜잭션의 UPDATE 가
        //   이 트랜잭션의 행 잠금에 막히는데 호출이 동기라 잠금을 놓을 수도 없다 — 자기 자신과의
        //   잠금 충돌로 락 타임아웃까지 매달렸다가 401 대신 500 이 나가고, 그동안 커넥션 2개를
        //   점유해 풀 고갈을 부른다. 여기(무잠금)서 마킹하면 충돌이 없다.
        //   expiresAt 은 불변이고 now 는 한 번만 계산하므로 이 판정은 잠금 뒤에도 같다.
        //   ACTIVE 만 거른다 — ROTATED 재사용 탐지는 아래 잠금 분기가 맡는다(만료돼도 재사용은
        //   재사용이다).
        RefreshTokenRepository.RotationPeek peeked = refreshTokenRepository.peekByTokenHash(tokenHash)
                .orElseThrow(() -> new RefreshTokenException(REFRESH_TOKEN_INVALID));
        if (peeked.getStatus() == RefreshTokenStatus.ACTIVE && now.isAfter(peeked.getExpiresAt())) {
            refreshTokenRevoker.expire(peeked.getId());
            throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
        }

        // 행 락 조회 — 동시 rotate의 후발이 선발 커밋 이후의 ROTATED를 보게 한다.
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new RefreshTokenException(REFRESH_TOKEN_INVALID));

        // 이미 회전된 토큰이 다시 왔다 = 누군가 사본을 들고 있다는 뜻이다.
        // 정상 클라이언트는 회전 후 옛 토큰을 버리므로 이 경로로 오지 않는다.
        // 이 계정의 살아 있는 세션을 전부 끊는다 — 탈취자와 사용자를 구분할 수 없기 때문이다.
        // ★ ROTATED 만 본다. REVOKED(로그아웃 · 세션 상한 정리 · 전 세션 끊기)는 정상 기기도 들고 있다가 나중에 보낸다 —
        //   그것까지 탈취로 보면, 비밀번호를 재설정하고 새 기기로 로그인한 뒤 옛 기기를 열기만 해도 새 기기까지 끊긴다.
        // ★ 단, 응답만 잃은 정상 기기도 옛 토큰을 다시 보낸다(회전 커밋과 응답 전달 사이 유실 — 망 끊김 · 앱 강제 종료).
        //   회전 직후(유예 창 안)이고 자식이 아직 안 쓰였으면(회전되지 않음) 그 자식만 끊어 이 기기만 다시 로그인시킨다 — 다른 기기는 무사.
        //   같은 옛 토큰이 창 안에 또 와도(앞서 끊은 자식) 401 만 준다. 자식에게 새 쌍을 주지는 않는다(보낸 쪽이 탈취자일 수 있다).
        //   창 밖이거나 자식이 이미 회전됐으면(쓰임) 전부 끊는다.
        if (token.getStatus() == RefreshTokenStatus.ROTATED) {
            Long userId = token.getUser().getId();
            if (withinReuseGrace(token, now) && refreshTokenRevoker.revokeUnusedChildOf(token.getId())) {
                log.warn("[refresh] 회전 직후 옛 토큰 재사용 — 유예 창 안이라 그 자식만 폐기. userId={}", userId);
                throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
            }
            log.warn("[refresh] 폐기된 토큰 재사용 감지 — 사용자 세션 전량 폐기. userId={}", userId);
            // 별도 트랜잭션에서 끊는다 — 바로 아래 예외가 이 트랜잭션을 롤백시키기 때문이다.
            refreshTokenRevoker.revokeAllFor(userId);
            throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
        }
        if (!token.isActive()) {
            throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
        }
        if (token.isExpired(now)) {
            // 도달 불가 방어 — expiresAt 은 불변이고 now 는 위와 같은 값이라 만료는 사전 검사가
            // 이미 걸렀다. ★ 여기서 revoker.expire 를 부르면 안 된다: 이 트랜잭션이 행 잠금을
            // 쥔 채 REQUIRES_NEW 가 같은 행을 갱신하면 자기 자신과 잠금 충돌한다(위 주석 참조).
            throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
        }

        token.rotate(now);
        return doIssue(token.getUser(), token);
    }

    // ROTATED 의 revokedAt 은 회전 시각이다(RefreshToken.rotate).
    private boolean withinReuseGrace(RefreshToken token, LocalDateTime now) {
        return token.getRevokedAt() != null && properties.reuseGrace() != null
                && !now.isAfter(token.getRevokedAt().plus(properties.reuseGrace()));
    }

    /**
     * 이 사용자의 세션을 <b>전부</b> 끊는다 — 리프레시와 <b>살아 있는 액세스 토큰까지</b>.
     *
     * <p>★ 리프레시만 끊으면 액세스 토큰이 발급 시점의 role 을 들고 수명(기본 30분)만큼
     * 그대로 통한다. 역할 변경·비밀번호 재설정·정지·탈퇴가 모두 "세션을 끊는다"는 뜻으로
     * 이 메서드를 부르는데, 절반만 끊고 있었다(적대적 리뷰 2026-09-17 2라운드).
     * 호출부마다 따로 챙기면 또 빠뜨리므로 <b>길목인 여기서</b> 함께 한다.
     */
    @Transactional
    public void revokeAllFor(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        refreshTokenRepository.findByUserIdAndStatusForUpdate(userId, RefreshTokenStatus.ACTIVE)
                .forEach(token -> token.revoke(now));
        cutAccessTokens(userId);
    }

    /**
     * 액세스 토큰 취소선. 발급한 토큰을 추적하지 않으므로 "이 시각 이전 발급은 무효"로 긋는다.
     *
     * <p>★ Redis 는 트랜잭션에 참여하지 않는다 — 호출부가 롤백해도 취소선은 남는다.
     * 그쪽이 안전한 실패다(사람들이 다시 로그인할 뿐, 권한이 살아남지 않는다).
     */
    private void cutAccessTokens(Long userId) {
        // ★ 잠금 조회여야 한다. 취소선(Redis)은 즉시 전역에 보이지만 역할 UPDATE 는 커밋해야
        //   보인다 — 그 사이에 로그인이 끼면 옛 역할 토큰이 취소선보다 늦은 iat 로 나간다.
        //   이 행을 커밋까지 잡고 있으면 발급 쪽(doIssue)이 같은 행에서 기다렸다가 새 역할을 읽는다.
        //   잠금 순서는 refresh_tokens → users 다(위 revokeAllFor 와 doIssue 가 같은 순서).
        //   ★ 알려진 예외 하나: 마지막 관리자 가드(findIdsByRoleForUpdate)는 users 를 먼저 잡는다.
        //     그래서 **자기 자신이 강등·탈퇴당하는 그 순간에 본인이 로그인**하면 교착이 날 수 있다.
        //     MySQL 이 즉시 탐지해 한쪽을 되돌리므로 매달리지 않고, 성립 조건이 이례적이라
        //     순서를 맞추려고 마지막 관리자 가드를 흔들지 않았다.
        //
        // ★ 이 Redis 쓰기를 **잠금 밖으로 빼지 말 것.** 행 잠금 안에서 왕복하는 게 눈에 거슬려
        //   AfterCommitRunner 로 미루고 싶어지는데(다른 곳에서 실제로 그렇게 쓴다),
        //   그러면 커밋과 취소선 사이에 창이 생겨 위의 경합이 그대로 돌아온다. 취소선은
        //   **이 행을 쥔 채로** 그어져야 한다. 대신 Redis 타임아웃을 짧게 잡아 최악을 막는다
        //   (application-prod.yml 의 timeout).
        userRepository.findAuthSnapshotForUpdate(userId).ifPresent(snapshot ->
                blacklistedTokenStore.revokeAllBefore(
                        snapshot.getUuid(), clock.instant(), jwtProperties.accessTokenTtl()));
    }

    // 로그아웃은 "그 토큰만" 끊는다. 다른 기기의 세션까지 죽이면 안 된다.
    @Transactional
    public void revokeIfOwnedBy(String rawToken, Long userId) {
        refreshTokenRepository.findByTokenHash(hasher.hash(rawToken))
                .filter(token -> token.getUser().getId().equals(userId))
                .filter(RefreshToken::isActive)
                .ifPresent(token -> token.revoke(LocalDateTime.now(clock)));
    }

    private RefreshTokenIssueResult doIssue(User user, RefreshToken parent) {
        LocalDateTime now = LocalDateTime.now(clock);

        List<RefreshToken> actives =
                refreshTokenRepository.findByUserIdAndStatusForUpdate(user.getId(), RefreshTokenStatus.ACTIVE);
        int excess = actives.size() - (properties.maxActivePerUser() - 1);
        for (int i = 0; i < excess; i++) {
            actives.get(i).revoke(now);
        }

        // ★ 토큰에 실을 역할은 **엔티티가 아니라 여기서** 가져온다. user.getRole() 은 트랜잭션
        //   첫 읽기 시점의 스냅샷이라, 비밀번호 검증(수백 ms) 동안 커밋된 강등을 보지 못한다.
        //   위 refresh_tokens 잠금 다음에 users 를 잠근다 — 취소 경로도 같은 순서라 교착이 없다.
        UserRepository.AuthSnapshot snapshot = userRepository.findAuthSnapshotForUpdate(user.getId())
                .orElseThrow(() -> new RefreshTokenException(REFRESH_TOKEN_INVALID));
        // 정지·탈퇴가 이 발급과 경합해 커밋됐을 수 있다. 회전은 상태를 다시 보지 않으면
        // 경합을 뚫고 살아남은 행이 죽은 계정에서 무기한 돌아간다.
        if (UserStatus.from(snapshot.getStatus()) != UserStatus.ACTIVE) {
            throw new RefreshTokenException(REFRESH_TOKEN_INVALID);
        }

        UserRole role = UserRole.from(snapshot.getRole());
        // 파트너의 능력은 승인된 부문이 연다 — 그 목록도 같은 자리에서 **잠금 읽기**로 읽는다.
        // 역할과 같은 이유다: 부문을 바꾸는 심사도 users 행을 먼저 잠그고 부문을 고치므로,
        // 이 users 잠금 뒤의 잠금 읽기는 심사의 커밋 전이거나 후 둘 중 하나만 본다.
        Set<PartnerDivisionType> divisions = role == UserRole.PARTNER
                ? approvedDivisionsOf(user.getId()) : Set.of();

        String raw = generateRawToken();
        RefreshToken saved = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hasher.hash(raw))
                .status(RefreshTokenStatus.ACTIVE)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .parentToken(parent)
                .build());

        return new RefreshTokenIssueResult(raw, saved.getId(), snapshot.getUuid(), role, divisions);
    }

    // 코드가 모르는 부문 이름(이름을 바꾼 뒤 남은 행 등)은 버린다 — 로그인 전체를 500 으로 만드는 것보다
    // 그 부문의 능력만 닫히는 쪽이 안전한 실패다.
    private Set<PartnerDivisionType> approvedDivisionsOf(Long userId) {
        Set<PartnerDivisionType> divisions = EnumSet.noneOf(PartnerDivisionType.class);
        for (String name : partnerDivisionRepository.findApprovedDivisionNamesForUpdate(userId)) {
            try {
                divisions.add(PartnerDivisionType.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("[refresh] 알 수 없는 파트너 부문을 건너뛴다. userId={} division={}", userId, name);
            }
        }
        return divisions;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
