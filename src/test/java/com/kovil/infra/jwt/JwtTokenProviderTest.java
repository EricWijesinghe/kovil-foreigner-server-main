package com.kovil.infra.jwt;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.infra.jwt.exception.JwtErrorCode;
import com.kovil.infra.jwt.exception.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String USER_UUID = "8f2b1c40-0000-4000-8000-000000000001";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    private static final String SECRET = base64Key((byte) 0x11);
    private static final String OTHER_SECRET = base64Key((byte) 0x22);

    private static String base64Key(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return Base64.getEncoder().encodeToString(key);
    }

    private static JwtProperties props(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(30), "kovil");
    }

    private static JwtTokenProvider provider(String secret, Instant at) {
        return new JwtTokenProvider(props(secret), Clock.fixed(at, ZONE));
    }

    @Nested
    @DisplayName("발급과 파싱")
    class IssueAndParse {

        @Test
        @DisplayName("발급한 토큰을 파싱하면 uuid와 역할이 그대로 돌아온다")
        void round_trip() {
            JwtTokenProvider sut = provider(SECRET, NOW);

            ParsedJwt parsed = sut.parse(sut.issueAccessToken(USER_UUID, UserRole.ADMIN));

            assertThat(parsed.userUuid()).isEqualTo(USER_UUID);
            assertThat(parsed.role()).isEqualTo(UserRole.ADMIN);
            assertThat(parsed.jti()).isNotBlank();
            assertThat(parsed.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
            // ★ iat 는 유저 축 취소선의 **유일한 입력**이다. 이걸 싣지 않으면(또는 null 이면)
            //   필터가 취소선과 비교할 값을 잃어 역할 변경·정지·도난 대응이 통째로 무력화된다.
            //   그런데 issuedAt 을 관찰하는 단언이 전 저장소에 하나도 없었다
            //   (적대적 리뷰 2026-09-17 3라운드).
            assertThat(parsed.issuedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("토큰에 DB PK는 담기지 않는다")
        void carries_no_database_pk() {
            JwtTokenProvider sut = provider(SECRET, NOW);
            String token = sut.issueAccessToken(USER_UUID, UserRole.USER);

            String payload = new String(Base64.getUrlDecoder()
                    .decode(token.split("\\.")[1]));

            assertThat(payload).contains(USER_UUID);
            assertThat(payload).doesNotContain("\"id\"").doesNotContain("userId");
        }
    }

    /*
     * 파트너의 능력은 승인된 부문이 연다 — 그 목록이 토큰의 divisions 클레임이다. 요청마다 DB 를 묻지 않는다.
     */
    @Nested
    @DisplayName("파트너 부문 클레임")
    class Divisions {

        @Test
        @DisplayName("파트너 토큰은 승인된 부문을 싣고 그대로 돌려준다")
        void partner_round_trip() {
            JwtTokenProvider sut = provider(SECRET, NOW);

            ParsedJwt parsed = sut.parse(sut.issueAccessToken(
                    USER_UUID, UserRole.PARTNER, Set.of(PartnerDivisionType.REAL_ESTATE_BROKER)));

            assertThat(parsed.role()).isEqualTo(UserRole.PARTNER);
            assertThat(parsed.approvedDivisions()).containsExactly(PartnerDivisionType.REAL_ESTATE_BROKER);
        }

        // 심사 중인 파트너 — 클레임은 있되 비어 있다. 기능이 하나도 열리지 않는다.
        @Test
        @DisplayName("승인된 부문이 없는 파트너는 빈 목록이다")
        void partner_without_approved_division() {
            JwtTokenProvider sut = provider(SECRET, NOW);

            assertThat(sut.parse(sut.issueAccessToken(USER_UUID, UserRole.PARTNER, Set.of()))
                    .approvedDivisions()).isEmpty();
        }

        // 파트너가 아닌 사람에게 부문이 따라붙을 길을 만들지 않는다 — 넘겨도 싣지 않는다.
        @Test
        @DisplayName("파트너가 아니면 부문을 넘겨도 클레임을 싣지 않는다")
        void non_partner_carries_no_divisions() {
            JwtTokenProvider sut = provider(SECRET, NOW);
            String token = sut.issueAccessToken(
                    USER_UUID, UserRole.USER, Set.of(PartnerDivisionType.REAL_ESTATE_BROKER));

            assertThat(payloadOf(token)).doesNotContain("divisions");
            assertThat(sut.parse(token).approvedDivisions()).isEmpty();
        }

        // 이 클레임이 생기기 전에 발급된 토큰(배포 직전 로그인한 유학생 · 관리자)도 그대로 통해야 한다.
        @Test
        @DisplayName("클레임이 없는 토큰은 빈 목록으로 읽는다")
        void missing_claim_reads_as_empty() {
            String token = signed(Map.of("role", "ADMIN"));

            ParsedJwt parsed = provider(SECRET, NOW).parse(token);

            assertThat(parsed.role()).isEqualTo(UserRole.ADMIN);
            assertThat(parsed.approvedDivisions()).isEmpty();
        }

        // 일부만 버리고 통과시키면 토큰과 DB 의 부문이 어긋난 채 돌아다닌다.
        @Test
        @DisplayName("모르는 부문 이름이 실린 토큰은 무효다")
        void unknown_division_is_invalid() {
            String token = signed(Map.of("role", "PARTNER", "divisions", List.of("NOT_A_DIVISION")));

            assertThatThrownBy(() -> provider(SECRET, NOW).parse(token))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN);
        }
    }

    @Nested
    @DisplayName("거절")
    class Rejection {

        /*
         * ★ 내부 담당자(MANAGER)와 옛 중개사(BROKER) 역할이 사라지기 전에 발급된 토큰이 수명(30분) 동안
         *   돌아다닌다. 서명은 맞으므로 역할 해석에서 터지는데, 그 예외가 그대로 새면 필터를 뚫고 500 이 된다.
         *   무효 토큰(→ 401)이어야 앱이 리프레시로 새 역할의 토큰을 받는다.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"MANAGER", "BROKER", "SOMETHING_ELSE"})
        @DisplayName("지금 없는 역할의 토큰은 500 이 아니라 INVALID_TOKEN 이다")
        void retired_role_is_invalid_token(String role) {
            String token = signed(Map.of("role", role));

            assertThatThrownBy(() -> provider(SECRET, NOW).parse(token))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("만료된 토큰은 EXPIRED_TOKEN로 거절된다")
        void expired() {
            String token = provider(SECRET, NOW).issueAccessToken(USER_UUID, UserRole.USER);
            JwtTokenProvider later = provider(SECRET, NOW.plus(Duration.ofMinutes(31)));

            assertThatThrownBy(() -> later.parse(token))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.EXPIRED_TOKEN);
        }

        @Test
        @DisplayName("만료 직전 토큰은 통과한다")
        void just_before_expiry() {
            String token = provider(SECRET, NOW).issueAccessToken(USER_UUID, UserRole.USER);
            JwtTokenProvider justBefore = provider(SECRET, NOW.plus(Duration.ofMinutes(29)));

            assertThat(justBefore.parse(token).userUuid()).isEqualTo(USER_UUID);
        }

        @Test
        @DisplayName("다른 키로 서명된 토큰은 INVALID_TOKEN로 거절된다")
        void wrong_signature() {
            String forged = provider(OTHER_SECRET, NOW).issueAccessToken(USER_UUID, UserRole.ADMIN);

            assertThatThrownBy(() -> provider(SECRET, NOW).parse(forged))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("발급자가 다른 토큰은 INVALID_TOKEN로 거절된다")
        void wrong_issuer() {
            JwtProperties foreign = new JwtProperties(SECRET, Duration.ofMinutes(30), "someone-else");
            String token = new JwtTokenProvider(foreign, Clock.fixed(NOW, ZONE))
                    .issueAccessToken(USER_UUID, UserRole.USER);

            assertThatThrownBy(() -> provider(SECRET, NOW).parse(token))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("형식이 깨진 문자열은 INVALID_TOKEN로 거절된다")
        void malformed() {
            assertThatThrownBy(() -> provider(SECRET, NOW).parse("not-a-jwt"))
                    .isInstanceOf(JwtException.class)
                    .extracting("errorCode")
                    .isEqualTo(JwtErrorCode.INVALID_TOKEN);
        }
    }

    @Nested
    @DisplayName("설정 오류")
    class Misconfiguration {

        @Test
        @DisplayName("시크릿이 너무 짧으면 첫 요청이 아니라 생성 시점에 터진다")
        void weak_secret_fails_at_construction() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> provider(tooShort, NOW))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("시크릿이 없으면 생성 시점에 터진다")
        void missing_secret_fails_at_construction() {
            assertThatThrownBy(() -> provider(null, NOW))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private static String payloadOf(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    // 운영 키로 서명된, 지금 코드가 발급하지 않는 모양의 토큰 — 배포 전에 발급된 토큰을 흉내 낸다.
    private static String signed(Map<String, Object> claims) {
        return Jwts.builder()
                .id("jti-1")
                .issuer("kovil")
                .subject(USER_UUID)
                .claims(claims)
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(30))))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)))
                .compact();
    }
}
