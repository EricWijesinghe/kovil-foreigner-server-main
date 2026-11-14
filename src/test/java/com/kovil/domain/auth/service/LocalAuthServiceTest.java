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
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserAuthRepository;
import com.kovil.common.ratelimit.RateLimitException;
import com.kovil.domain.auth.exception.AuthErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.BDDMockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class LocalAuthServiceTest {

    private static final String IP = "203.0.113.7";

    @Mock TokenIssuer tokenIssuer;
    @Mock SignUpProcessor signUpProcessor;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserAuthRepository userAuthRepository;
    @Mock LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks LocalAuthService service;

    private static User user(UserStatus status) {
        return User.builder().uuid("uuid-1").role(UserRole.USER)
                .status(status).preferredLanguage("ko").build();
    }

    private static UserAuth auth(User user) {
        return UserAuth.builder().user(user).authType(AuthType.LOCAL)
                .identifier("a@b.com").passwordHash("encoded").build();
    }

    @Nested
    @DisplayName("가입")
    class SignUp {

        private LocalSignUpRequest request() {
            return new LocalSignUpRequest("tok", "  A@B.com  ", "password1", "Nguyen", "ko");
        }

        // 대소문자·공백이 다른 같은 이메일로 계정이 두 개 생기면 안 된다.
        @Test
        @DisplayName("이메일은 소문자·트림으로 정규화되어 중복 판정과 저장에 쓰인다")
        void email_is_normalized() {
            given(userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(false);
            given(signUpProcessor.createUser(eq("tok"), eq("Nguyen"), anyString(), eq("ko"), eq(UserRole.USER))).willReturn(user(UserStatus.ACTIVE));
            given(passwordEncoder.encode("password1")).willReturn("encoded");
            given(tokenIssuer.issue(any(User.class))).willReturn(new TokenPair("at", "rt", 1800));

            service.signUp(request());

            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getIdentifier()).isEqualTo("a@b.com");
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("encoded");
        }

        /*
         * ★ existsBy 검사와 INSERT 사이에 같은 이메일이 먼저 들어올 수 있다. 그때 uq_user_auth_active
         *   가 잡는데 그대로 두면 진 쪽에 500 이 나간다 — 순차 실행에서는 DUPLICATE_EMAIL 인데
         *   경합에서만 답이 달라진다(적대적 리뷰 2026-09-02).
         */
        @Test
        @DisplayName("이메일 유니크 경합은 500 이 아니라 DUPLICATE_EMAIL 로 끊는다")
        void email_unique_race_becomes_duplicate_email() {
            given(userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(false);
            given(signUpProcessor.createUser(eq("tok"), eq("Nguyen"), anyString(), eq("ko"), eq(UserRole.USER)))
                    .willReturn(user(UserStatus.ACTIVE));
            given(passwordEncoder.encode("password1")).willReturn("encoded");
            given(userAuthRepository.saveAndFlush(any(UserAuth.class))).willThrow(
                    new DataIntegrityViolationException("dup",
                            new ConstraintViolationException(
                                    "dup", null, "uq_user_auth_active")));

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("DUPLICATE_EMAIL");
        }

        // 제약 이름을 안 보고 싸잡으면 엉뚱한 위반이 "이미 사용 중인 이메일"로 둔갑한다.
        @Test
        @DisplayName("수단 유니크가 아닌 위반은 번역하지 않고 그대로 올린다")
        void other_constraint_is_not_translated_on_signup() {
            given(userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(false);
            given(signUpProcessor.createUser(eq("tok"), eq("Nguyen"), anyString(), eq("ko"), eq(UserRole.USER)))
                    .willReturn(user(UserStatus.ACTIVE));
            given(passwordEncoder.encode("password1")).willReturn("encoded");
            given(userAuthRepository.saveAndFlush(any(UserAuth.class))).willThrow(
                    new DataIntegrityViolationException("fk",
                            new ConstraintViolationException(
                                    "fk", null, "fk_user_auth_user")));

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("비밀번호 원문은 저장되지 않는다")
        void raw_password_is_never_stored() {
            given(userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(false);
            given(signUpProcessor.createUser(eq("tok"), eq("Nguyen"), anyString(), eq("ko"), eq(UserRole.USER))).willReturn(user(UserStatus.ACTIVE));
            given(passwordEncoder.encode("password1")).willReturn("encoded");
            given(tokenIssuer.issue(any(User.class))).willReturn(new TokenPair("at", "rt", 1800));

            service.signUp(request());

            ArgumentCaptor<UserAuth> captor = ArgumentCaptor.forClass(UserAuth.class);
            then(userAuthRepository).should().saveAndFlush(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("password1");
        }

        // 이메일 중복은 인증을 소비하기 전에 걸러야 한다 — 소비해 버리면 사용자는
        // 이메일만 바꿔 다시 시도할 수 없고 휴대폰 인증부터 다시 해야 한다.
        // 검증(assertUsableSignupToken)은 지나가지만 소비(createUser)에는 닿지 않는다.
        @Test
        @DisplayName("이메일이 중복이면 인증을 소비하지 않는다")
        void duplicate_email_does_not_consume_verification() {
            given(userAuthRepository.existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(true);

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("DUPLICATE_EMAIL");

            then(signUpProcessor).should(never())
                    .createUser(anyString(), any(), anyString(), any(), any());
        }

        /*
         * ★ 검사 순서가 계약이다. 이메일 중복 검사가 토큰 검증 앞에 있으면, 토큰 없는 요청이
         *   400/409 응답 차이만으로 이메일 가입 여부를 캐낸다 — SMS 한 통 없이 도는 열거
         *   오라클이다(적대적 리뷰 2026-09-02). 순서를 되돌리면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("토큰이 무효면 이메일이 중복이어도 DUPLICATE_EMAIL 을 답하지 않는다")
        void invalid_token_wins_over_duplicate_email() {
            willThrow(new SignUpException(
                    AuthErrorCode.VERIFICATION_TOKEN_INVALID))
                    .given(signUpProcessor).assertUsableSignupToken("tok");

            assertThatThrownBy(() -> service.signUp(request()))
                    .isInstanceOf(SignUpException.class)
                    .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                    .isEqualTo("VERIFICATION_TOKEN_INVALID");

            // 가입 여부 판정 자체가 실행되면 안 된다 — 타이밍 차이로도 샌다.
            then(userAuthRepository).should(never())
                    .existsByAuthTypeAndIdentifierAndDeletedAtIsNull(any(), anyString());
        }
    }

    @Nested
    @DisplayName("로그인")
    class SignIn {

        private final LocalSignInRequest request = new LocalSignInRequest("A@B.com", "password1");

        @Test
        @DisplayName("성공하면 토큰과 사용자 정보를 주고 실패로 세지 않는다")
        void success_issues_tokens() {
            User user = user(UserStatus.ACTIVE);
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.of(auth(user)));
            given(passwordEncoder.matches("password1", "encoded")).willReturn(true);
            given(tokenIssuer.issue(user)).willReturn(new TokenPair("at", "rt", 1800));

            SignInResponse response = service.signIn(request, IP);

            assertThat(response.accessToken()).isEqualTo("at");
            assertThat(response.refreshToken()).isEqualTo("rt");
            // FE가 JWT를 디코딩하지 않고도 갱신 시점을 잡을 수 있어야 한다.
            assertThat(response.accessTokenExpiresIn()).isEqualTo(1800);
            assertThat(response.userUuid()).isEqualTo("uuid-1");
            then(loginAttemptLimiter).should(never()).recordFailure(anyString(), anyString());
        }

        // 계정 없음과 비밀번호 불일치가 같은 코드로 나가야 가입 여부가 새지 않는다.
        @Test
        @DisplayName("계정 없음과 비밀번호 불일치가 같은 코드로 나가고 둘 다 실패로 센다")
        void unknown_account_and_wrong_password_are_indistinguishable() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.signIn(request, IP))
                    .isInstanceOf(SignInException.class)
                    .extracting(e -> ((SignInException) e).getErrorCode().getCode())
                    .isEqualTo("INVALID_CREDENTIALS");
            then(loginAttemptLimiter).should().recordFailure("a@b.com", IP);
        }

        // 코드가 같아도 계정이 없을 때 BCrypt 를 건너뛰면 응답이 수십 ms 빨라져 시간으로 가입 여부가 샌다.
        @Test
        @DisplayName("계정이 없어도 같은 비용의 비밀번호 검사를 한 번 돌린다(응답 시간 열거 방지)")
        void unknown_account_still_runs_one_password_check() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.signIn(request, IP)).isInstanceOf(SignInException.class);

            // 더미 해시는 생성자에서 이미 만들어 두었다 — 요청 중에 encode 를 돌리면 첫 요청만 느려진다.
            then(passwordEncoder).should().matches(eq("password1"), any());
            then(passwordEncoder).should(times(1)).encode(anyString()); // once, at construction
            then(loginAttemptLimiter).should().recordFailure("a@b.com", IP);
            then(tokenIssuer).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("비밀번호가 틀리면 실패로 세고 같은 코드를 준다")
        void wrong_password_records_failure() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.of(auth(user(UserStatus.ACTIVE))));
            given(passwordEncoder.matches("password1", "encoded")).willReturn(false);

            assertThatThrownBy(() -> service.signIn(request, IP))
                    .isInstanceOf(SignInException.class)
                    .extracting(e -> ((SignInException) e).getErrorCode().getCode())
                    .isEqualTo("INVALID_CREDENTIALS");
            then(loginAttemptLimiter).should().recordFailure("a@b.com", IP);
            then(tokenIssuer).shouldHaveNoInteractions();
        }

        // 자격 증명이 맞았으므로 브루트포스 신호가 아니다 — 세면 정지 계정 주인이
        // 자기 IP를 스스로 잠그게 된다.
        @Test
        @DisplayName("정지 계정은 SUSPENDED_USER로 거절하되 실패로 세지 않는다")
        void suspended_user_is_rejected_without_counting() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.of(auth(user(UserStatus.SUSPENDED))));
            given(passwordEncoder.matches("password1", "encoded")).willReturn(true);

            assertThatThrownBy(() -> service.signIn(request, IP))
                    .isInstanceOf(SignInException.class)
                    .extracting(e -> ((SignInException) e).getErrorCode().getCode())
                    .isEqualTo("SUSPENDED_USER");
            then(loginAttemptLimiter).should(never()).recordFailure(anyString(), anyString());
        }

        // 정지와 갈라 두는 이유는 화면이 서로 다른 막다른 길이기 때문이다 —
        // 정지는 운영팀 연락, 탈퇴는 재가입이다.
        @Test
        @DisplayName("탈퇴 계정은 USER_NOT_ACTIVE로 갈린다")
        void withdrawn_user_gets_a_different_code() {
            given(userAuthRepository.findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType.LOCAL, "a@b.com"))
                    .willReturn(Optional.of(auth(user(UserStatus.WITHDRAWN))));
            given(passwordEncoder.matches("password1", "encoded")).willReturn(true);

            assertThatThrownBy(() -> service.signIn(request, IP))
                    .isInstanceOf(SignInException.class)
                    .extracting(e -> ((SignInException) e).getErrorCode().getCode())
                    .isEqualTo("USER_NOT_ACTIVE");
        }

        @Test
        @DisplayName("한도를 넘었으면 비밀번호를 검사하기 전에 막는다")
        void limiter_runs_before_password_check() {
            BDDMockito.willThrow(new RateLimitException(60))
                    .given(loginAttemptLimiter).check("a@b.com", IP);

            assertThatThrownBy(() -> service.signIn(request, IP))
                    .isInstanceOf(RateLimitException.class);

            then(userAuthRepository).shouldHaveNoInteractions();
            // 생성자가 더미 해시를 한 번 만든다(encode) — 막혔다면 비교(matches)는 한 번도 돌지 않아야 한다.
            then(passwordEncoder).should(never()).matches(any(), any());
        }
    }
}
