package com.kovil.infra.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PKCE S256 대조. 이 클래스가 무너지면 커스텀 스킴으로 돌아오는 code 를 가로챈 앱이
 * 그대로 우리 토큰을 받아 간다 — 느슨하게 통과시키는 분기가 하나라도 생기면 안 된다.
 */
class PkceValidatorTest {

    // RFC 7636 §4.2 대로 계산한 실제 짝이다. 상수를 고쳐야 한다면 반드시 함께 다시 계산할 것.
    private static final String VERIFIER = "wonjoomin-foreigner-pkce-test-verifier-0123456789";
    private static final String CHALLENGE = "EI7uJUdGyTH_Hx7H28FU3awPzco6gxxv10wChDLeuPg";

    private final PkceValidator validator = new PkceValidator();

    @Nested
    @DisplayName("짝 대조")
    class Matching {

        @Test
        @DisplayName("SHA-256 base64url 짝이 맞으면 통과한다")
        void correct_pair_passes() {
            assertThat(validator.matches(VERIFIER, CHALLENGE)).isTrue();
        }

        @Test
        @DisplayName("verifier 가 한 글자만 달라도 거부한다")
        void one_character_off_fails() {
            assertThat(validator.matches(VERIFIER.replace('0', '1'), CHALLENGE)).isFalse();
        }

        // 가로챈 쪽에는 verifier 가 아예 없다 — 그때 빈 값으로 통과되면 방어가 무의미하다.
        @Test
        @DisplayName("verifier 가 없으면 거부한다")
        void missing_verifier_fails() {
            assertThat(validator.matches(null, CHALLENGE)).isFalse();
            assertThat(validator.matches("", CHALLENGE)).isFalse();
        }

        @Test
        @DisplayName("challenge 가 없으면 거부한다 — 짝지을 대상이 없다")
        void missing_challenge_fails() {
            assertThat(validator.matches(VERIFIER, null)).isFalse();
            assertThat(validator.matches(VERIFIER, "")).isFalse();
        }
    }

    @Nested
    @DisplayName("형식 강제 (RFC 7636 §4.1)")
    class Format {

        // 짧은 verifier 는 무차별 대입의 여지를 준다 — 43자 미만은 형식 단계에서 끊는다.
        @Test
        @DisplayName("verifier 가 43자 미만이면 대조 전에 거부한다")
        void verifier_too_short_fails() {
            assertThat(validator.matches("a".repeat(42), CHALLENGE)).isFalse();
        }

        @Test
        @DisplayName("verifier 가 128자를 넘으면 거부한다")
        void verifier_too_long_fails() {
            assertThat(validator.matches("a".repeat(129), CHALLENGE)).isFalse();
        }

        // base64url 이 아닌 표준 base64(+ / =)를 흘려보내면 클라이언트마다 다른 값이 오간다.
        @Test
        @DisplayName("unreserved 가 아닌 문자가 섞인 verifier 는 거부한다")
        void verifier_with_reserved_characters_fails() {
            assertThat(validator.matches("a".repeat(42) + "+", CHALLENGE)).isFalse();
            assertThat(validator.matches("a".repeat(42) + "/", CHALLENGE)).isFalse();
            assertThat(validator.matches("a".repeat(42) + "=", CHALLENGE)).isFalse();
        }

        @Test
        @DisplayName("challenge 는 패딩 없는 43자 base64url 만 받는다")
        void challenge_format_is_pinned() {
            assertThat(validator.isValidChallenge(CHALLENGE)).isTrue();
            assertThat(validator.isValidChallenge(CHALLENGE + "=")).isFalse();
            assertThat(validator.isValidChallenge(CHALLENGE.substring(0, 42))).isFalse();
            assertThat(validator.isValidChallenge(CHALLENGE.replace('_', '/'))).isFalse();
            assertThat(validator.isValidChallenge(null)).isFalse();
        }
    }
}
