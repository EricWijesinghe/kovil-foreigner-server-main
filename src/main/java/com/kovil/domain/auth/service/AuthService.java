package com.kovil.domain.auth.service;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.auth.dto.response.TokenRefreshResponse;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.exception.UserException;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.user.exception.UserErrorCode.USER_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final TokenIssuer tokenIssuer;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final BlacklistedTokenStore blacklistedTokenStore;

    @Transactional
    public TokenRefreshResponse refresh(String rawRefreshToken) {
        return TokenRefreshResponse.from(tokenIssuer.reissue(rawRefreshToken));
    }

    // 리프레시 토큰은 폐기하고, 이미 나가 있는 액세스 토큰은 남은 수명만큼 블랙리스트에 담는다.
    // 둘 중 하나만 하면 한쪽 토큰으로 계속 쓸 수 있다.
    @Transactional
    public void logout(AuthenticatedUser principal, String rawRefreshToken) {
        User user = userRepository.findByUuidAndDeletedAtIsNull(principal.userUuid())
                .orElseThrow(() -> new UserException(USER_NOT_FOUND));

        refreshTokenService.revokeIfOwnedBy(rawRefreshToken, user.getId());
        blacklistedTokenStore.add(principal.jti(), principal.remaining());
    }
}
