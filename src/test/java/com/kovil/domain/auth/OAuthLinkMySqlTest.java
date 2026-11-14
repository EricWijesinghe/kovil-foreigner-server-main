package com.kovil.domain.auth;

import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.OAuthException;
import com.kovil.domain.auth.exception.SignUpException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.service.OAuthAuthService;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.AuthType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.UserService;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.oauth.OAuthTempTokenStore;
import com.kovil.infra.oauth.dto.OAuthTempData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * 소셜 연결의 같은 번호 거절을 **진짜 MySQL** 에서 본다. 단위 테스트는 SignUpProcessor 를 목으로 두어
 * 번호 경합의 제약 이름이 번역 조건에 걸리는지, 계정 생성 · 인증 소비 뒤의 실패를 롤백이 실제로 되돌리는지 보지 못한다.
 *
 * 스프링을 그 MySQL 에 붙여 띄우고(Flyway 로 최신까지), 실행마다 고유한 값으로 행을 심는다(지우지 않는다).
 * Redis 에 있는 두 증표(인증 토큰 · 소셜 임시 토큰)만 목으로 둔다.
 * AUTH_DB_URL 이 없으면 통째로 건너뛴다. 그 이름의 스키마만 쓴다.
 */
@EnabledIfEnvironmentVariable(named = "AUTH_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${AUTH_DB_URL}",
        "spring.datasource.username=${AUTH_DB_USER:root}",
        "spring.datasource.password=${AUTH_DB_PASSWORD:}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.flyway.enabled=true"})
class OAuthLinkMySqlTest {

    private static final String REQUIRED_SCHEMA = "wjf_auth";

    @Autowired OAuthAuthService oAuthAuthService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired PhoneVerificationRepository verificationRepository;
    @Autowired Encryptor encryptor;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean PhoneVerificationStore verificationStore;
    @MockitoBean OAuthTempTokenStore tempTokenStore;
    // 액세스 토큰 취소선(Redis)은 이 테스트의 관심사가 아니다.
    @MockitoBean BlacklistedTokenStore blacklistedTokenStore;

    @BeforeAll
    static void guard() {
        assertThat(System.getenv("AUTH_DB_URL"))
                .describedAs("AUTH_DB_URL 은 %s 스키마만 가리킬 수 있다", REQUIRED_SCHEMA)
                .contains("/" + REQUIRED_SCHEMA);
    }

    private void activeUser(String phoneHash) {
        String uuid = UUID.randomUUID().toString();
        userRepository.save(User.builder().uuid(uuid).name("NGUYEN")
                .nickname(uuid.substring(0, 8)).nicknameNormalized(uuid.substring(0, 8))
                .phone(encryptor.encrypt("01000000031")).phoneHash(phoneHash)
                .email(uuid.substring(0, 8) + "@example.test").preferredLanguage("ko")
                .status(UserStatus.ACTIVE).role(UserRole.USER).build());
    }

    // 인증 한 건 = verification 행 + Redis 의 토큰 → 행 id.
    private String verifiedToken(String phoneHash) {
        PhoneVerification verification = verificationRepository.save(PhoneVerification.builder()
                .phone(encryptor.encrypt("01000000031")).phoneHash(phoneHash)
                .purpose(VerificationPurpose.SIGNUP).verifiedAt(LocalDateTime.now()).build());
        String token = "ver-" + UUID.randomUUID();
        given(verificationStore.findVerificationIdByToken(token)).willReturn(Optional.of(verification.getId()));
        return token;
    }

    private String tempToken(String sub) {
        return tempToken(sub, sub + "@example.test");
    }

    private String tempToken(String sub, String email) {
        String token = "temp-" + UUID.randomUUID();
        given(tempTokenStore.find(token)).willReturn(Optional.of(new OAuthTempData(AuthType.GOOGLE, sub, email, null)));
        return token;
    }

    private Object consumedAt(String verificationToken) {
        Long id = verificationStore.findVerificationIdByToken(verificationToken).orElseThrow();
        return jdbc.queryForObject("SELECT consumed_at FROM phone_verifications WHERE id = ?", Object.class, id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    @DisplayName("실 MySQL: 같은 번호 계정이 있으면 DUPLICATE_PHONE, 인증은 소비되지 않고 소셜 수단도 계정도 생기지 않는다")
    void existing_phone_is_rejected_and_leaves_nothing() {
        String phoneHash = "d102-" + UUID.randomUUID();
        activeUser(phoneHash);
        String sub = "google-" + UUID.randomUUID();
        String verificationToken = verifiedToken(phoneHash);
        String tempToken = tempToken(sub);

        assertThatThrownBy(() -> oAuthAuthService.link(tempToken, verificationToken, "ko"))
                .isInstanceOf(SignUpException.class)
                .extracting(e -> ((SignUpException) e).getErrorCode().getCode())
                .isEqualTo("DUPLICATE_PHONE");

        assertThat(consumedAt(verificationToken)).isNull();
        assertThat(count("SELECT COUNT(*) FROM users WHERE phone_hash = ?", phoneHash)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM user_auth WHERE identifier = ?", sub)).isZero();
        then(verificationStore).should(never()).deleteToken(verificationToken);
        then(tempTokenStore).should(never()).delete(anyString());
    }

    // 위 거절은 계정 생성 · 인증 소비 전에 끊긴다. 이메일 없음은 둘 다 끝난 뒤에 끊기므로 롤백이 실제로 되돌려야 한다 —
    // 남으면 그 번호 슬롯을 수단 없는 계정이 잡고, 재시도는 VERIFICATION_ALREADY_USED, 새 인증은 DUPLICATE_PHONE 이 된다.
    @Test
    @DisplayName("실 MySQL: 계정 생성 · 인증 소비 뒤의 OAUTH_EMAIL_REQUIRED 는 롤백되어 계정도 소비도 남지 않는다")
    void failure_after_consume_is_rolled_back() {
        String phoneHash = "d102-email-" + UUID.randomUUID();
        String sub = "google-" + UUID.randomUUID();
        String verificationToken = verifiedToken(phoneHash);
        String tempToken = tempToken(sub, null);

        assertThatThrownBy(() -> oAuthAuthService.link(tempToken, verificationToken, "ko"))
                .isInstanceOf(OAuthException.class)
                .extracting(e -> ((OAuthException) e).getErrorCode().getCode())
                .isEqualTo("OAUTH_EMAIL_REQUIRED");

        assertThat(consumedAt(verificationToken)).isNull();
        assertThat(count("SELECT COUNT(*) FROM users WHERE phone_hash = ?", phoneHash)).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_auth WHERE identifier = ?", sub)).isZero();
        then(verificationStore).should(never()).deleteToken(verificationToken);
        then(tempTokenStore).should(never()).delete(anyString());
    }

    // 같은 번호로 서로 다른 소셜 계정 둘이 동시에 연결 — 하나만 가입되고, 진 쪽은 순차와 같은 DUPLICATE_PHONE 이어야 한다.
    // 경합이 한 번도 안 나도(20회 모두 순차) 통과한다. 경합 경로를 탔는지는 실행 로그의
    // "Duplicate entry … for key 'users.uq_users_phone_hash'" 줄 수로 본다(순차 거절은 INSERT 전에 끊겨 그 줄이 없다).
    @Test
    @DisplayName("실 MySQL: 같은 번호 동시 연결은 계정 하나로 끝나고, 진 쪽은 DUPLICATE_PHONE(CONFLICT · 500 아님)")
    void concurrent_same_phone_link_ends_in_one_account() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> unexpected = new ArrayList<>();
        try {
            for (int round = 0; round < 20; round++) {
                String phoneHash = "d102-race-" + UUID.randomUUID();
                List<String> subs = List.of("google-" + UUID.randomUUID(), "google-" + UUID.randomUUID());
                List<String> verificationTokens = List.of(verifiedToken(phoneHash), verifiedToken(phoneHash));
                List<String> tempTokens = List.of(tempToken(subs.get(0)), tempToken(subs.get(1)));
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    int n = i;
                    calls.add(pool.submit(() -> {
                        start.await();
                        return oAuthAuthService.link(tempTokens.get(n), verificationTokens.get(n), "ko");
                    }));
                }
                start.countDown();
                int succeeded = 0;
                int loser = -1;
                for (int i = 0; i < 2; i++) {
                    try {
                        calls.get(i).get(30, TimeUnit.SECONDS);
                        succeeded++;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof SignUpException signUp
                                && "DUPLICATE_PHONE".equals(signUp.getErrorCode().getCode())) {
                            loser = i;
                        } else {
                            unexpected.add(e.getCause());
                        }
                    }
                }
                assertThat(succeeded).isEqualTo(1);
                assertThat(loser).isNotEqualTo(-1);
                int winner = 1 - loser;
                assertThat(count("SELECT COUNT(*) FROM users WHERE phone_hash = ?", phoneHash)).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM user_auth WHERE identifier = ?", subs.get(winner))).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM user_auth WHERE identifier = ?", subs.get(loser))).isZero();
                assertThat(consumedAt(verificationTokens.get(winner))).isNotNull();
                assertThat(consumedAt(verificationTokens.get(loser))).isNull();
                assertThat(jdbc.queryForObject("SELECT email FROM users WHERE phone_hash = ?", String.class, phoneHash))
                        .isEqualTo(subs.get(winner) + "@example.test");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(unexpected).isEmpty();
        System.out.println("[d102-mysql] 같은 번호 동시 연결 20회 — 매 회 계정 1 · 진 쪽 DUPLICATE_PHONE");
    }

    // V38 두 칸이 실제 스키마에 있고, Apple 가입이 그 칸에 암호문 · client_id 를 남기고, 탈퇴가 비우는지 진짜 MySQL 에서 본다.
    // 엔티티에 칸이 있어도 마이그레이션이 빠지면 H2(create-drop)는 초록이고 운영은 가입이 500 으로 죽는다.
    @Test
    @DisplayName("실 MySQL: Apple 가입은 수단 행에 토큰 암호문 · client_id 를 남기고, 탈퇴하면 두 칸이 비고 수단이 해제된다")
    void apple_link_stores_token_and_withdrawal_clears_it() {
        String phoneHash = "apple-" + UUID.randomUUID();
        String sub = "001234." + UUID.randomUUID();
        String encrypted = encryptor.encrypt("r.apple-refresh-" + UUID.randomUUID());
        String verificationToken = verifiedToken(phoneHash);
        String tempToken = "temp-" + UUID.randomUUID();
        given(tempTokenStore.find(tempToken)).willReturn(Optional.of(new OAuthTempData(
                AuthType.APPLE, sub, "x" + UUID.randomUUID().toString().substring(0, 8) + "@privaterelay.appleid.com",
                null, encrypted, "com.kovil.web")));

        String userUuid = oAuthAuthService.link(tempToken, verificationToken, "ko").userUuid();

        assertThat(jdbc.queryForObject("SELECT provider_refresh_token FROM user_auth WHERE identifier = ?",
                String.class, sub)).isEqualTo(encrypted);
        assertThat(jdbc.queryForObject("SELECT provider_client_id FROM user_auth WHERE identifier = ?",
                String.class, sub)).isEqualTo("com.kovil.web");
        assertThat(jdbc.queryForObject("SELECT auth_type FROM user_auth WHERE identifier = ?", String.class, sub))
                .isEqualTo("APPLE");

        userService.withdraw(userUuid);

        assertThat(jdbc.queryForObject("SELECT provider_refresh_token FROM user_auth WHERE identifier = ?",
                String.class, sub)).isNull();
        assertThat(jdbc.queryForObject("SELECT provider_client_id FROM user_auth WHERE identifier = ?",
                String.class, sub)).isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM user_auth WHERE identifier = ?", Object.class, sub))
                .isNotNull();
    }

    // Apple 연결 철회 알림. 세션과 토큰 칸만 비우고 수단 행은 살아 있어야 같은 sub 로 원래 계정에 다시 들어온다.
    // 잠금 순서(refresh_tokens → users → user_auth)가 진짜 InnoDB 에서 막히지 않는지도 같이 본다.
    @Test
    @DisplayName("실 MySQL: Apple 철회 알림은 세션 · 토큰 칸만 비우고 수단은 남긴다, 두 번 와도 같다")
    void apple_consent_revoked_keeps_link() {
        String phoneHash = "apple-revoke-" + UUID.randomUUID();
        String sub = "001234." + UUID.randomUUID();
        String verificationToken = verifiedToken(phoneHash);
        String tempToken = "temp-" + UUID.randomUUID();
        given(tempTokenStore.find(tempToken)).willReturn(Optional.of(new OAuthTempData(
                AuthType.APPLE, sub, "x" + UUID.randomUUID().toString().substring(0, 8) + "@privaterelay.appleid.com",
                null, encryptor.encrypt("r.apple-refresh-" + UUID.randomUUID()), "com.kovil.app")));
        oAuthAuthService.link(tempToken, verificationToken, "ko");
        String activeSessions = "SELECT COUNT(*) FROM refresh_tokens r JOIN user_auth a ON a.user_id = r.user_id "
                + "WHERE a.identifier = ? AND r.status = 'ACTIVE'";
        assertThat(count(activeSessions, sub)).isEqualTo(1);

        assertThat(userService.endAppleSession(sub)).isTrue();
        assertThat(userService.endAppleSession(sub)).isTrue();

        assertThat(count(activeSessions, sub)).isZero();
        assertThat(jdbc.queryForObject("SELECT provider_refresh_token FROM user_auth WHERE identifier = ?",
                String.class, sub)).isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM user_auth WHERE identifier = ?", Object.class, sub))
                .isNull();
        assertThat(userService.endAppleSession("001234.unknown-" + UUID.randomUUID())).isFalse();
    }
}
