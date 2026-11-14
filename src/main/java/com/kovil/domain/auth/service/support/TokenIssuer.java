package com.kovil.domain.auth.service.support;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.refreshtoken.dto.result.RefreshTokenIssueResult;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.infra.jwt.JwtProperties;
import com.kovil.infra.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;

    // ★ 역할은 user 엔티티가 아니라 refresh 결과에서 가져온다 — 아래 reissue 와 같은 출처다.
    //   엔티티의 role 은 트랜잭션 스냅샷이라 이 발급과 경합한 강등을 보지 못한다. issue 쪽은
    //   그 출처가 달라 강등된 사람이 옛 권한 토큰을 받아 갔다(적대적 리뷰 2026-09-17 3라운드).
    //   승인된 파트너 부문도 같은 출처(users 잠금 뒤의 잠금 읽기)에서 온다.
    public TokenPair issue(User user) {
        return pairOf(refreshTokenService.issue(user));
    }

    public TokenPair reissue(String rawRefreshToken) {
        return pairOf(refreshTokenService.rotate(rawRefreshToken));
    }

    private TokenPair pairOf(RefreshTokenIssueResult refresh) {
        return new TokenPair(
                jwtTokenProvider.issueAccessToken(
                        refresh.userUuid(), refresh.role(), refresh.approvedDivisions()),
                refresh.rawToken(),
                jwtProperties.accessTokenTtl().toSeconds(),
                refresh.approvedDivisions());
    }
}
