package com.kovil.domain.auth.service;

import com.kovil.common.tx.AfterCommitRunner;
import com.kovil.domain.auth.dto.request.AccountLookupRequest;
import com.kovil.domain.auth.dto.response.AccountLookupResponse;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.SignInException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.kovil.domain.auth.exception.AuthErrorCode.RESET_ACCOUNT_NOT_FOUND;
import static com.kovil.domain.auth.exception.AuthErrorCode.SUSPENDED_USER;
import static com.kovil.domain.auth.exception.AuthErrorCode.USER_NOT_ACTIVE;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_ALREADY_USED;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_TOKEN_INVALID;

/**
 * 아이디(이메일) 찾기. 비밀번호 재설정과 같은 축으로 본인을 확인한다 — 휴대폰이다.
 *
 * <p>★ 이메일을 가리지 않는다: 호출자는 이미 계정을 지탱하는 번호의 소유를 증명했고,
 * 같은 증명으로 비밀번호를 바꿔 계정을 통째로 가져갈 수 있다. 가리는 것은 연출일 뿐이고
 * 정작 로그인해야 하는 본인이 아이디를 못 읽는다.
 */
@Service
@RequiredArgsConstructor
public class AccountLookupService {

    private final PhoneVerificationStore verificationStore;
    private final PhoneVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final UserAuthRepository userAuthRepository;
    private final Clock clock;

    @Transactional
    public AccountLookupResponse lookup(AccountLookupRequest request) {
        PhoneVerification verification = loadUnconsumed(request.verificationToken());

        // 탈퇴 계정은 phone_hash 가 비어 있어 여기서 자연히 걸러진다 —
        // 거절당해 탈퇴 처리된 사람은 "계정 없음"을 보고 다시 가입하면 된다.
        User user = userRepository.findByPhoneHash(verification.getPhoneHash())
                .orElseThrow(() -> new SignInException(RESET_ACCOUNT_NOT_FOUND));
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new SignInException(SUSPENDED_USER);
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new SignInException(USER_NOT_ACTIVE);
        }

        List<UserAuth> auths = userAuthRepository.findAllByUserId(user.getId()).stream()
                .filter(auth -> auth.getDeletedAt() == null)
                .toList();

        String email = auths.stream()
                .filter(auth -> auth.getAuthType() == AuthType.LOCAL)
                .map(UserAuth::getIdentifier)
                .findFirst()
                .orElse(null);

        // 소셜로만 가입한 사람에게 "계정 없음"을 주면 그 사람은 영영 못 들어간다 —
        // 어느 소셜로 들어가야 하는지가 유일한 도움이다(비밀번호 재설정과 같은 판단).
        List<String> providers = auths.stream()
                .filter(auth -> auth.getAuthType() != AuthType.LOCAL)
                .map(auth -> auth.getAuthType().name())
                .distinct()
                .toList();

        // 인증은 여기서 소비된다 — 같은 토큰으로 두 번 조회할 수 없다.
        verification.consume(user.getId(), LocalDateTime.now(clock));
        AfterCommitRunner.run("account-lookup-token",
                () -> verificationStore.deleteToken(request.verificationToken()));

        return new AccountLookupResponse(email, providers);
    }

    private PhoneVerification loadUnconsumed(String verificationToken) {
        Long verificationId = verificationStore.findVerificationIdByToken(verificationToken)
                .orElseThrow(() -> new SignInException(VERIFICATION_TOKEN_INVALID));

        PhoneVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new SignInException(VERIFICATION_TOKEN_INVALID));

        if (verification.isConsumed()) {
            throw new SignInException(VERIFICATION_ALREADY_USED);
        }
        // 가입용 인증으로 남의 아이디를 캐는 경로를 막는다 — 목적이 다르면 무효다.
        if (verification.getPurpose() != VerificationPurpose.PASSWORD_RESET) {
            throw new SignInException(VERIFICATION_TOKEN_INVALID);
        }
        return verification;
    }
}
