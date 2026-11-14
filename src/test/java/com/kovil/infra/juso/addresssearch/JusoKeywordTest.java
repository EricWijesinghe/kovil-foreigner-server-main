package com.kovil.infra.juso.addresssearch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 행정안전부 보안 장비는 특수문자 · SQL 예약어가 섞인 검색어를 공격으로 보고 우리 서버 IP 를 막는다 — 보내기 전에 거른다.
class JusoKeywordTest {

    @Test
    @DisplayName("주소 글자(한글 · 영문 · 숫자 · 공백 · - . , ( ) ·)는 그대로 둔다")
    void keeps_address_characters() {
        assertThat(JusoKeyword.of("성남시 분당구 불정로 6 (정자동, 그린팩토리)")).contains("성남시 분당구 불정로 6 (정자동, 그린팩토리)");
        assertThat(JusoKeyword.of("빛가람동 235-1")).contains("빛가람동 235-1");
    }

    @Test
    @DisplayName("특수문자는 공백으로 바꾸고 · 겹친 공백과 하이픈은 하나로 접는다")
    void replaces_special_characters() {
        assertThat(JusoKeyword.of("관악로'; <script>")).contains("관악로 script");
        assertThat(JusoKeyword.of("  관악로   1--2  ")).contains("관악로 1-2");
    }

    @Test
    @DisplayName("SQL 예약어가 든 검색어는 보내지 않는다(대소문자 무시 · 낱말 속 포함)")
    void blocks_sql_words() {
        assertThat(JusoKeyword.of("관악로 select")).isEmpty();
        assertThat(JusoKeyword.of("DROP 관악로")).isEmpty();
        assertThat(JusoKeyword.of("KOREA")).isEmpty();
    }

    @Test
    @DisplayName("두 글자 미만 · null · 거른 뒤 비면 보내지 않는다")
    void too_short_is_empty() {
        assertThat(JusoKeyword.of(null)).isEmpty();
        assertThat(JusoKeyword.of("가")).isEmpty();
        assertThat(JusoKeyword.of("!!@@")).isEmpty();
        assertThat(JusoKeyword.of("관악")).contains("관악");
    }
}
