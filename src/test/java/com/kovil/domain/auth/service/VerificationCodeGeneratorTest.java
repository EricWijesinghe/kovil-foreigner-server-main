package com.kovil.domain.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCodeGeneratorTest {

    private final VerificationCodeGenerator generator = new VerificationCodeGenerator();

    // 앞자리 0이 잘리면 "012345"가 "12345"가 되어 자릿수 검증(^\\d{6}$)에서 걸린다.
    @Test
    @DisplayName("항상 6자리 숫자다 — 앞자리 0도 유지된다")
    void always_six_digits() {
        IntStream.range(0, 2000).forEach(i ->
                assertThat(generator.generate()).matches("^\\d{6}$"));
    }

    @Test
    @DisplayName("연속 생성이 서로 다르다(고정값이 아니다)")
    void not_constant() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 200).forEach(i -> codes.add(generator.generate()));

        assertThat(codes).hasSizeGreaterThan(100);
    }
}
