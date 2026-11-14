package com.kovil.domain.auth.service;

import com.kovil.domain.auth.dto.TokenPair;
import com.kovil.domain.auth.dto.request.LocalSignInRequest;
import com.kovil.domain.auth.dto.request.LocalSignUpRequest;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.auth.exception.SignInException;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.service.support.LoginAttemptLimiter;
import com.kovil.domain.auth.service.support.SignUpProcessor;
import com.kovil.domain.auth.service.support.TokenIssuer;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.domain.user.enums.UserStatus;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.kovil.domain.auth.exception.AuthErrorCode.DUPLICATE_EMAIL;
import static com.kovil.domain.auth.exception.AuthErrorCode.INVALID_CREDENTIALS;
import static com.kovil.domain.auth.exception.AuthErrorCode.SUSPENDED_USER;
import static com.kovil.domain.auth.exception.AuthErrorCode.USER_NOT_ACTIVE;

@Service
public class LocalAuthService {

    // 인코더 설정(cost)과 같은 비용의 해시여야 시간이 맞는다 — 상수 문자열이 아니라 인코더로 만든다.
    // 생성 시점에 한 번 만든다: 처음 쓸 때 만들면 그 첫 요청만 BCrypt 를 두 번 돌아 '계정 없음'이 시간으로 샌다.
    private static final String TIMING_DUMMY_SECRET = "kovil-login-timing-equalizer";

    private final TokenIssuer tokenIssuer;
    private final SignUpProcessor signUpProcessor;
    private final PasswordEncoder passwordEncoder;
    private final UserAuthRepository userAuthRepository;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final String timingDummyHash;

    public LocalAuthService(TokenIssuer tokenIssuer, SignUpProcessor signUpProcessor, PasswordEncoder passwordEncoder,
                            UserAuthRepository userAuthRepository, LoginAttemptLimiter loginAttemptLimiter) {
        this.tokenIssuer = tokenIssuer;
        this.signUpProcessor = signUpProcessor;
        this.passwordEncoder = passwordEncoder;
        this.userAuthRepository = userAuthRepository;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.timingDummyHash = passwordEncoder.encode(TIMING_DUMMY_SECRET);
    }

    private String timingDummyHash() {
        return timingDummyHash;
    }

    @Transactional
    public SignInResponse signUp(LocalSignUpRequest request) {
        User user = registerLocal(request.verificationToken(), request.email(), request.password(),
                request.name(), request.preferredLanguage(), UserRole.USER);
        return SignInResponse.of(tokenIssuer.issue(user), user);
    }

    /*
     * 이메일 가입의 몸통 — 유학생 가입과 파트너 가입이 **같은 관문**을 지난다(인증 토큰 → 이메일 중복 →
     * 계정 → 로그인 수단). 파트너 가입이 이 순서를 따로 적으면 열거 오라클 방어(아래 첫 주석)가 한쪽에서 샌다.
     * 토큰 발급은 호출부 몫이다 — 파트너 가입은 계정 뒤에 부문 · 사무소까지 만든 다음에 발급한다.
     */
    @Transactional
    public User registerLocal(String verificationToken, String rawEmail, String rawPassword, String name,
                              String preferredLanguage, UserRole role) {
        String email = normalizeEmail(rawEmail);

        signUpProcessor.assertUsableSignupToken(verificationToken);
        if (userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, email)) {
            throw new SignUpException(DUPLICATE_EMAIL);
        }

        User user = signUpProcessor.createUser(verificationToken, name, email, preferredLanguage, role);

        insertLocalAuth(user, email, rawPassword);
        return user;
    }

    /**
     * 위 existsBy 검사와 이 INSERT 사이에 같은 이메일이 먼저 들어올 수 있다. 그때는
     * uq_user_auth_active 가 잡는데 **그대로 두면 사용자에게 500 이 나간다** — 순차 실행에서는
     * DUPLICATE_EMAIL 인데 경합에서만 답이 달라진다(적대적 리뷰 2026-09-02).
     *
     * 제약 이름을 확인해 수단 유니크만 번역한다. 번호 유니크는 SignUpProcessor 가 이미
     * 자기 답으로 번역했으므로 여기까지 오지 않고, 그 밖의 위반은 그대로 올려보낸다.
     *
     * saveAndFlush 여야 한다 — save 만 하면 위반이 커밋 시점에 터져 이 try 를 빠져나간다.
     */
    private void insertLocalAuth(User user, String email, String rawPassword) {
        try {
            userAuthRepository.saveAndFlush(UserAuth.builder()
                    .user(user)
                    .authType(AuthType.LOCAL)
                    .identifier(email)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .build());
        } catch (DataIntegrityViolationException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase().contains("uq_user_auth_active")) {
                throw new SignUpException(DUPLICATE_EMAIL);
            }
            throw e;
        }
    }

    @Transactional
    public SignInResponse signIn(LocalSignInRequest request, String clientIp) {
        String email = normalizeEmail(request.email());

        // 실패 누적이 한도를 넘었으면 비밀번호를 검사하기 전에 막는다.
        loginAttemptLimiter.check(email, clientIp);

        Optional<UserAuth> found = userAuthRepository
                .findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, email);
        // 계정이 없어도 같은 비용의 BCrypt 를 한 번 돌린다 — 건너뛰면 응답 시간으로 가입 여부가 샌다.
        String hash = found.map(UserAuth::getPasswordHash).orElseGet(this::timingDummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);

        // 계정 없음도 자격 불일치와 같은 실패로 센다 — 가입 여부를 응답 차이로 알아낼 수 없게.
        if (found.isEmpty() || !matches) {
            loginAttemptLimiter.recordFailure(email, clientIp);
            throw new SignInException(INVALID_CREDENTIALS);
        }
        UserAuth userAuth = found.get();

        User user = userAuth.getUser();
        if (!user.isActive()) {
            // 자격 증명은 맞았다 — 브루트포스 신호가 아니므로 실패로 세지 않는다.
            //
            // 정지와 탈퇴를 갈라 주는 이유: 화면이 서로 다른 막다른 길이다(정지는 운영팀 연락,
            // 탈퇴는 재가입). 비밀번호를 맞힌 뒤에만 도달하므로 계정 존재 여부가 새지 않는다.
            throw new SignInException(
                    user.getStatus() == UserStatus.SUSPENDED
                            ? SUSPENDED_USER : USER_NOT_ACTIVE);
        }

        TokenPair tokens = tokenIssuer.issue(user);
        return SignInResponse.of(tokens, user);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
