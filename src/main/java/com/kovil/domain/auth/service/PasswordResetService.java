package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.request.PasswordResetRequest;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.SignInException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.refreshtoken.service.RefreshTokenService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.common.tx.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.kovil.domain.auth.exception.AuthErrorCode.RESET_ACCOUNT_NOT_FOUND;
import static com.kovil.domain.auth.exception.AuthErrorCode.RESET_NOT_LOCAL_ACCOUNT;
import static com.kovil.domain.auth.exception.AuthErrorCode.SUSPENDED_USER;
import static com.kovil.domain.auth.exception.AuthErrorCode.USER_NOT_ACTIVE;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_ALREADY_USED;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_TOKEN_INVALID;

// 이메일이 아니라 휴대폰이 본인 확인 축이다 — 신원 앵커가 phone_hash라서다.
// 이메일 링크 방식은 이 서비스에서 오히려 약하다(이메일은 앵커가 아니고 소유 검증도 없다).
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final PhoneVerificationStore verificationStore;
    private final PhoneVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    @Transactional
    public void reset(PasswordResetRequest request) {
        PhoneVerification verification = loadUnconsumed(request.verificationToken());

        // 탈퇴 계정은 phone_hash가 비워져 있어 여기서 자연히 걸러진다.
        User user = userRepository.findByPhoneHash(verification.getPhoneHash())
                .orElseThrow(() -> new SignInException(RESET_ACCOUNT_NOT_FOUND));
        // 정지와 탈퇴를 갈라 준다 — 화면이 서로 다른 막다른 길이다(signIn 과 같은 규칙).
        // 실제로 이 분기에 도달하는 상태는 SUSPENDED 뿐이다: 탈퇴는 phone_hash 가 비어
        // 위 조회에서 이미 걸러진다. 정지자가 SMS(과금)까지 통과한 뒤 원인 불명의 일반
        // 오류를 보게 하면 안 된다 — 고객센터 안내가 답이다(적대적 리뷰 2026-09-02).
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new SignInException(SUSPENDED_USER);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new SignInException(USER_NOT_ACTIVE);
        }

        // 소셜 전용 계정엔 바꿀 비밀번호가 없다 — 어느 로그인으로 가입했는지 알려주는 게 유일한 도움이다.
        UserAuth localAuth = userAuthRepository
                .findByUserIdAndAuthTypeAndDeletedAtIsNull(user.getId(), AuthType.LOCAL)
                .orElseThrow(() -> new SignInException(RESET_NOT_LOCAL_ACCOUNT));

        localAuth.changePassword(passwordEncoder.encode(request.newPassword()));

        // 인증은 여기서 소비된다 — 같은 토큰으로 두 번 재설정할 수 없다.
        // Redis 삭제는 커밋 이후다 — 아래 revokeAllFor 의 행 잠금 경합 등으로 롤백되면
        // consume 은 되돌아가는데 키만 사라져 SMS 인증(과금)부터 다시 해야 한다.
        verification.consume(user.getId(), LocalDateTime.now(clock));
        AfterCommitRunner.run("password-reset-token",
                () -> verificationStore.deleteToken(request.verificationToken()));

        // 비밀번호를 바꾸는 상황은 유출 의심 상황이다 — 살아 있는 세션을 전부 끊는다.
        refreshTokenService.revokeAllFor(user.getId());

        log.info("[auth] 비밀번호 재설정 user={}", user.getId());
    }

    private PhoneVerification loadUnconsumed(String verificationToken) {
        Long verificationId = verificationStore.findVerificationIdByToken(verificationToken)
                .orElseThrow(() -> new SignInException(VERIFICATION_TOKEN_INVALID));

        PhoneVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new SignInException(VERIFICATION_TOKEN_INVALID));

        if (verification.isConsumed()) {
            throw new SignInException(VERIFICATION_ALREADY_USED);
        }
        // 가입용 인증으로 남의 비밀번호를 바꾸는 경로를 막는다 — 목적이 다르면 무효다.
        if (verification.getPurpose() != VerificationPurpose.PASSWORD_RESET) {
            throw new SignInException(VERIFICATION_TOKEN_INVALID);
        }
        return verification;
    }
}
