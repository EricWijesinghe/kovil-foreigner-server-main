package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.response.OAuthLoginResponse;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.infra.oauth.OAuthClientRouter;
import com.kovil.infra.oauth.OAuthReturnTarget;
import com.kovil.infra.oauth.PkceValidator;
import com.kovil.infra.oauth.OAuthStateStore;
import com.kovil.infra.oauth.OAuthTempTokenStore;
import com.kovil.infra.oauth.dto.OAuthTempData;
import com.kovil.infra.oauth.dto.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import com.kovil.common.exception.CommonErrorCode;
import com.kovil.common.tx.AfterCommitRunner;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_ALREADY_LINKED;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_EMAIL_REQUIRED;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_PKCE_INVALID;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_STATE_INVALID;
import static com.kovil.domain.auth.exception.AuthErrorCode.SUSPENDED_USER;
import static com.kovil.domain.auth.exception.AuthErrorCode.OAUTH_TEMP_TOKEN_EXPIRED;
import static com.kovil.domain.auth.exception.AuthErrorCode.USER_NOT_ACTIVE;

@Service
@RequiredArgsConstructor
public class OAuthAuthService {

    private final TokenIssuer tokenIssuer;
    private final PkceValidator pkceValidator;
    private final OAuthStateStore stateStore;
    private final SignUpProcessor signUpProcessor;
    private final OAuthTempTokenStore tempTokenStore;
    private final OAuthClientRouter oAuthClientRouter;
    private final UserAuthRepository userAuthRepository;

    public String buildAuthorizationUri(AuthType provider, String codeChallenge, OAuthReturnTarget returnTarget) {
        if (!pkceValidator.isValidChallenge(codeChallenge)) {
            throw new OAuthException(OAUTH_PKCE_INVALID);
        }
        return oAuthClientRouter.getClient(provider)
                .buildAuthorizationUri(stateStore.generate(codeChallenge, returnTarget), codeChallenge);
    }

    @Transactional
    public OAuthLoginResponse login(AuthType provider, String code, String state,
                                    String userPayload, String codeVerifier) {
        String codeChallenge = stateStore.consume(state);
        if (codeChallenge == null) {
            throw new OAuthException(OAUTH_STATE_INVALID);
        }

        if (!pkceValidator.matches(codeVerifier, codeChallenge)) {
            throw new OAuthException(OAUTH_PKCE_INVALID);
        }

        OAuthUserInfo userInfo = oAuthClientRouter.getClient(provider)
                .getUserInfo(code, state, userPayload, codeVerifier);

        return signInOrHold(provider, userInfo);
    }

    // 웹 code 교환과 Apple 네이티브가 신원을 확인한 뒤 만나는 자리. 두 경로의 분기가 갈라지면 안 된다.
    // 토큰 암호문은 Apple 만 싣는다(탈퇴 철회용). 나머지 수단은 null 이고 건드리지 않는다.
    @Transactional
    public OAuthLoginResponse signInOrHold(AuthType provider, OAuthUserInfo userInfo) {
        return userAuthRepository
                .findByAuthTypeAndIdentifierAndDeletedAtIsNull(provider, userInfo.providerUserId())
                .map(userAuth -> {
                    User user = userAuth.getUser();
                    if (user.getStatus() == UserStatus.SUSPENDED) {
                        throw new OAuthException(SUSPENDED_USER);
                    }
                    if (!user.isActive()) {
                        throw new OAuthException(USER_NOT_ACTIVE);
                    }
                    if (userInfo.encryptedRefreshToken() != null) {
                        userAuth.replaceProviderRefreshToken(
                                userInfo.encryptedRefreshToken(), userInfo.refreshTokenClientId());
                    }
                    return OAuthLoginResponse.existingUser(SignInResponse.of(tokenIssuer.issue(user), user));
                })
                .orElseGet(() -> OAuthLoginResponse.phoneVerificationRequired(
                        tempTokenStore.save(new OAuthTempData(
                                provider, userInfo.providerUserId(), userInfo.email(), userInfo.name(),
                                userInfo.encryptedRefreshToken(), userInfo.refreshTokenClientId()))));
    }

    @Transactional
    public SignInResponse link(String oauthToken, String verificationToken, String preferredLanguage) {
        OAuthTempData tempData = tempTokenStore.find(oauthToken)
                .orElseThrow(() -> new OAuthException(OAUTH_TEMP_TOKEN_EXPIRED));

        User user = signUpProcessor.createUser(
                verificationToken, tempData.name(), null, preferredLanguage, UserRole.USER);

        String email = tempData.email();
        if (email == null || email.isBlank()) {
            throw new OAuthException(OAUTH_EMAIL_REQUIRED);
        }
        user.changeEmailIfAbsent(email);

        userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(
                        tempData.provider(), tempData.providerUserId())
                .ifPresent(existing -> {
                    throw new OAuthException(OAUTH_ALREADY_LINKED);
                });
        insertLink(user, tempData);

        AfterCommitRunner.run("oauth-temp-token", () -> tempTokenStore.delete(oauthToken));
        return SignInResponse.of(tokenIssuer.issue(user), user);
    }

    private void insertLink(User user, OAuthTempData tempData) {
        try {
            userAuthRepository.saveAndFlush(UserAuth.builder()
                    .user(user)
                    .authType(tempData.provider())
                    .identifier(tempData.providerUserId())
                    .providerRefreshToken(tempData.encryptedProviderRefreshToken())
                    .providerClientId(tempData.providerClientId())
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase().contains("uq_user_auth_active")) {
                throw new OAuthException(CommonErrorCode.CONFLICT);
            }
            throw e;
        }
    }
}
