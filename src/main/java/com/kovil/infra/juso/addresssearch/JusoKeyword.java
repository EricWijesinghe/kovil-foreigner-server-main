package com.kovil.infra.juso.addresssearch;

import java.util.Optional;
import java.util.regex.Pattern;

/*
 * 행정안전부 보안 장비는 검색어에 SQL 예약어나 특수문자가 섞이면 공격으로 보고 **부른 쪽 IP 를 막는다**.
 * 부르는 쪽이 우리 서버라, 한 사람이 친 글자가 운영 서버 IP 를 막아 모두의 검색이 멈출 수 있다. 그래서 보내기 전에 거른다(CLEANUS 와 같은 규칙).
 *
 * 주소에 쓰는 글자만 남기고(한글 · 영문 · 숫자 · 공백 · - . , ( ) ·) 나머지는 공백으로 바꾼다.
 * 예약어는 행정안전부 예제와 같은 목록 · 같은 방식(대소문자 무시, 낱말 속도 포함)으로 보고, 있으면 보내지 않는다.
 * "KOREA" 처럼 영문 낱말 속에 OR 이 든 검색이 막히지만 결과 없음으로 끝난다 — IP 가 막히는 것보다 싸다.
 */
final class JusoKeyword {

    private static final Pattern NOT_ADDRESS_CHAR = Pattern.compile("[^가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 \\-.,()·]");
    private static final Pattern DASHES = Pattern.compile("-{2,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern SQL_WORD = Pattern.compile(
            "OR|SELECT|INSERT|DELETE|UPDATE|CREATE|DROP|EXEC|UNION|FETCH|DECLARE|TRUNCATE",
            Pattern.CASE_INSENSITIVE);

    private JusoKeyword() {
    }

    static Optional<String> of(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = NOT_ADDRESS_CHAR.matcher(raw).replaceAll(" ");
        cleaned = DASHES.matcher(cleaned).replaceAll("-");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").strip();
        // 한 글자는 행정안전부가 오류로 돌려준다(E0008) — 부를 이유가 없다.
        if (cleaned.length() < 2 || SQL_WORD.matcher(cleaned).find()) {
            return Optional.empty();
        }
        return Optional.of(cleaned);
    }
}
