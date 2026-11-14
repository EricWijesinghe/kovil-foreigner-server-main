package com.kovil.domain.auth.service;

import com.kovil.domain.auth.exception.PhoneVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberNormalizerTest {

    private final PhoneNumberNormalizer normalizer = new PhoneNumberNormalizer();

    // 같은 번호가 표기 차이로 다른 phone_hash가 되면 "한 번호 = 한 계정"(INV-01)이 깨진다.
    // uq_users_phone_hash가 서로 다른 값으로 보고 둘 다 통과시키기 때문이다.
    @Test
    @DisplayName("표기가 달라도 같은 번호는 하나의 형태로 정규화된다")
    void normalizes_to_single_form() {
        assertThat(normalizer.normalize("01012345678")).isEqualTo("01012345678");
        assertThat(normalizer.normalize("010-1234-5678")).isEqualTo("01012345678");
        assertThat(normalizer.normalize("010 1234 5678")).isEqualTo("01012345678");
        assertThat(normalizer.normalize("+82 10-1234-5678")).isEqualTo("01012345678");
        assertThat(normalizer.normalize("+821012345678")).isEqualTo("01012345678");
        assertThat(normalizer.normalize("821012345678")).isEqualTo("01012345678");
    }

    @Test
    @DisplayName("011·016·017·018·019 구번호도 허용한다")
    void allows_legacy_prefixes() {
        assertThat(normalizer.normalize("0111234567")).isEqualTo("0111234567");
        assertThat(normalizer.normalize("01612345678")).isEqualTo("01612345678");
        assertThat(normalizer.normalize("0191234567")).isEqualTo("0191234567");
    }

    // FR-101 — 한국 번호가 아니면 가입 자체가 막힌다. 우리 신원 앵커가 phone_hash라서,
    // 해외 번호를 받으면 앵커의 유일성 근거가 사라진다.
    @Test
    @DisplayName("한국 휴대폰이 아니면 PHONE_NOT_KR로 거절한다")
    void rejects_non_kr_mobile() {
        for (String phone : new String[]{
                "+8613800138000",      // 중국
                "+14155552671",        // 미국
                "+84912345678",        // 베트남
                "0212345678",          // 서울 유선
                "0212345",             // 너무 짧음
                "010123456789",        // 너무 김
                "01512345678",         // 없는 접두어
                "abcdefghijk"}) {
            assertThatThrownBy(() -> normalizer.normalize(phone))
                    .as("phone=%s", phone)
                    .isInstanceOf(PhoneVerificationException.class)
                    .extracting(e -> ((PhoneVerificationException) e).getErrorCode().getCode())
                    .isEqualTo("PHONE_NOT_KR");
        }
    }

    @Test
    @DisplayName("비어 있으면 PHONE_REQUIRED로 구분해서 거절한다")
    void rejects_blank_separately() {
        for (String phone : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> normalizer.normalize(phone))
                    .isInstanceOf(PhoneVerificationException.class)
                    .extracting(e -> ((PhoneVerificationException) e).getErrorCode().getCode())
                    .isEqualTo("PHONE_REQUIRED");
        }
    }
}
