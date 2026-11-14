package com.kovil.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessNumberGeneratorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    private final BusinessNumberGenerator generator = new BusinessNumberGenerator();

    @Test
    @DisplayName("업무번호는 접두어-yyMMdd-6자리 형식이다")
    void format() {
        assertThat(generator.generate(BusinessNumberPrefix.RENTAL_REQUEST, DATE))
                .matches("RQ-260810-[0-9A-Z]{6}");
        assertThat(generator.generate(BusinessNumberPrefix.LISTING, DATE))
                .matches("LS-260810-[0-9A-Z]{6}");
    }

    // 맡은 사무소가 전화로 불러주는 번호다. 선례의 알파벳은 Crockford Base32 — I L O U를 뺀다.
    @Test
    @DisplayName("혼동 문자 I L O U는 쓰지 않는다")
    void excludes_ambiguous_characters() {
        String joined = IntStream.range(0, 500)
                .mapToObj(i -> generator.generate(BusinessNumberPrefix.RENTAL_REQUEST, DATE).substring(10))
                .reduce("", String::concat);

        assertThat(joined).doesNotContain("I", "L", "O", "U");
    }

    @Test
    @DisplayName("연속 생성분이 서로 다르다")
    void generates_distinct_values() {
        Set<String> generated = new HashSet<>();
        IntStream.range(0, 1000)
                .forEach(i -> generated.add(generator.generate(BusinessNumberPrefix.RENTAL_REQUEST, DATE)));

        assertThat(generated).hasSize(1000);
    }

    // 날짜를 넘기지 않는 오버로드는 LocalDate.now() — 즉 서버 타임존을 그대로 따른다.
    // 서비스 타임존을 보장해야 하는 호출부는 generate(prefix, LocalDate.now(clock))을 써야 한다.
    @Test
    @DisplayName("날짜를 넘기지 않으면 서버 기본 타임존의 오늘이 찍힌다")
    void no_arg_overload_uses_system_default_zone() {
        String expected = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));

        assertThat(generator.generate(BusinessNumberPrefix.RENTAL_REQUEST))
                .startsWith("RQ-" + expected + "-");
    }
}
