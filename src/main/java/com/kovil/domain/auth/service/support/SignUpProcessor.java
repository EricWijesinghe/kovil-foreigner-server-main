package com.kovil.domain.auth.service.support;

import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.language.service.LanguageQueryService;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserStatusHistory;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.AccountBlockService;
import com.kovil.domain.user.service.NicknameService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import com.kovil.common.exception.CommonErrorCode;
import com.kovil.common.tx.AfterCommitRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.kovil.domain.auth.exception.AuthErrorCode.DUPLICATE_PHONE;
import static com.kovil.domain.auth.exception.AuthErrorCode.SUSPENDED_USER;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_ALREADY_USED;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_TOKEN_INVALID;

@Component
@RequiredArgsConstructor
public class SignUpProcessor {

    private static final String DEFAULT_LANGUAGE = "en";

    private final PhoneVerificationStore verificationStore;
    private final PhoneVerificationRepository verificationRepository;
    private final UserRepository userRepository;
    private final AccountBlockService accountBlockService;
    private final LanguageQueryService languageQueryService;
    private final NicknameService nicknameService;
    private final Clock clock;

    @Transactional
    public User createUser(String verificationToken, String name, String email, String preferredLanguage) {
        return createUser(verificationToken, name, email, preferredLanguage, UserRole.USER);
    }

    // 역할은 가입 경로가 정함 — 유학생 가입은 USER, 파트너 가입은 PARTNER. 관리자는 여기서 만들 수 없음.
    @Transactional
    public User createUser(String verificationToken, String name, String email, String preferredLanguage,
                           UserRole role) {
        if (role == UserRole.ADMIN) {
            throw new IllegalArgumentException("관리자는 가입으로 만들 수 없다");
        }
        PhoneVerification verification = loadUnconsumed(verificationToken);

        userRepository.findByPhoneHash(verification.getPhoneHash()).ifPresent(this::handleExisting);

        try {
            return createAndConsume(verification, verificationToken, name, email, preferredLanguage, role);
        } catch (DataIntegrityViolationException e) {
            throw translatePhoneRace(e);
        }
    }

    /*
     * ★ LocalAuthService 가 이메일 중복 검사 **앞**에 부른다. 순서가 뒤집히면 토큰 없는 요청이
     *   400/409 응답 차이만으로 이메일 가입 여부를 캐낸다 — SMS 한 통 없이 도는 열거 오라클이다
     *   (적대적 리뷰 2026-09-02). 로그인이 계정 없음과 비밀번호 불일치를 구분하지 않는 것과
     *   같은 원칙: 가입 여부는 번호를 실제로 소유한 사람에게만 드러난다.
     */
    @Transactional(readOnly = true)
    public void assertUsableSignupToken(String verificationToken) {
        loadUnconsumed(verificationToken);
    }

    private PhoneVerification loadUnconsumed(String verificationToken) {
        Long verificationId = verificationStore.findVerificationIdByToken(verificationToken)
                .orElseThrow(() -> new SignUpException(VERIFICATION_TOKEN_INVALID));

        PhoneVerification verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new SignUpException(VERIFICATION_TOKEN_INVALID));

        if (verification.isConsumed()) {
            throw new SignUpException(VERIFICATION_ALREADY_USED);
        }
        if (verification.getPurpose() != VerificationPurpose.SIGNUP) {
            throw new SignUpException(VERIFICATION_TOKEN_INVALID);
        }

        if (accountBlockService.isPhoneBlocked(verification.getPhoneHash())) {
            throw new SignUpException(SUSPENDED_USER);
        }
        return verification;
    }

    private User createAndConsume(PhoneVerification verification, String verificationToken,
                                  String name, String email, String preferredLanguage, UserRole role) {
        LocalDateTime now = LocalDateTime.now(clock);

        NicknameService.Allocation nickname = nicknameService.allocate();

        User user = userRepository.saveAndFlush(User.builder()
                .uuid(UUID.randomUUID().toString())
                .name(name)
                .email(email)
                .nickname(nickname.nickname())
                .nicknameNormalized(nickname.normalized())
                .isNicknameCustom(false)
                .phone(verification.getPhone())
                .phoneHash(verification.getPhoneHash())
                .phoneVerifiedAt(verification.getVerifiedAt())
                .preferredLanguage(resolveLanguage(preferredLanguage))
                .status(UserStatus.ACTIVE)
                .role(role)
                .build());

        // 계정 타임라인의 시작점. 없으면 정지·탈퇴 행만 떠 있어 전이의 앞뒤가 끊긴다 —
        // "이 사람은 언제부터 있었나"를 이력만으로 답할 수 없다. from_status 가 NULL 인 행이 곧 가입이다.
        accountBlockService.recordStatusChange(user.getId(), null, UserStatus.ACTIVE,
                user.getId(), UserStatusHistory.ACTOR_SELF, null);

        verification.consume(user.getId(), now);
        deleteTokenAfterCommit(verificationToken);

        return user;
    }

    // 같은 번호의 기존 계정이 있으면 상태가 결론을 정한다.
    private void handleExisting(User existing) {
        switch (existing.getStatus()) {
            case ACTIVE -> throw new SignUpException(DUPLICATE_PHONE);
            case SUSPENDED -> throw new SignUpException(SUSPENDED_USER);
            // 탈퇴 계정은 번호 슬롯을 비워 재가입을 허용한다(FR-110). 계약·수납 이력은 그대로 남는다.
            case WITHDRAWN -> {
                existing.releasePhoneHash();
                userRepository.saveAndFlush(existing);
            }
        }
    }

    /**
     * 가입(로컬 · 소셜 연결)의 번호 동시성 문제. 같은 번호로 두 요청이 동시에 오면 uq_users_phone_hash 가 잡는데,
     * 순차 실행의 답이 DUPLICATE_PHONE 이므로(handleExisting) 경합도 같은 답으로 전달.
     * 그대로 두면 진 쪽 사용자에게만 500 이 나가 답이 갈림.
     */
    private RuntimeException translatePhoneRace(DataIntegrityViolationException e) {
        if (isViolationOf(e, "uq_users_phone_hash")) {
            return new SignUpException(DUPLICATE_PHONE);
        }
        return translateSharedRace(e);
    }

    /**
     * 번호 말고 다른 제약 위반.
     *
     * ★ 닉네임 유니크는 CONFLICT 다. NicknameService 는 사다리를 소진하면 점유된 후보를
     *   알면서 그대로 반환하므로(log.error 뒤), 사전 소진 상태에서는 이 위반이 확률이 아니라
     *   확정으로 난다 — 무번역이면 신규 가입 전부가 500 이다(적대적 리뷰 2026-09-02).
     *   재시도하면 다른 후보가 뽑히니 "잠시 후 다시"가 정확한 답이고, 인증 토큰은 롤백으로
     *   살아남아 재진입이 실제로 성공한다. FK 같은 예상 밖 위반만 그대로 올려보낸다.
     */
    private RuntimeException translateSharedRace(DataIntegrityViolationException e) {
        if (isViolationOf(e, "uq_users_nickname_normalized")) {
            return new SignUpException(CommonErrorCode.CONFLICT);
        }
        return e;
    }

    private boolean isViolationOf(DataIntegrityViolationException e, String constraint) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getConstraintName() != null
                && violation.getConstraintName().toLowerCase().contains(constraint);
    }

    /**
     * Redis 토큰 삭제는 커밋 이후다 — 트랜잭션 안에서 지우면 롤백이 되돌려 주지 않는다.
     * 소셜 연결은 여기를 지난 뒤에도 호출부(OAuthAuthService.link)가 OAUTH_EMAIL_REQUIRED
     * 등으로 실패할 수 있음. 삭제 실패를 삼키는 이유는 AfterCommitRunner 참조.
     */
    private void deleteTokenAfterCommit(String verificationToken) {
        AfterCommitRunner.run("phone-verification-token",
                () -> verificationStore.deleteToken(verificationToken));
    }

    private String resolveLanguage(String requested) {
        return languageQueryService.isSupported(requested) ? requested : DEFAULT_LANGUAGE;
    }
}
