package com.kovil.domain.rentalrequest.enums;

/**
 * **질문의 입력 유형.** 질문(1단) 행에만 값이 있고 항목(2단) 행은 NULL이다.
 *
 * <p>이름이 SelectionType 인 것은 역사적 이유다 — 처음엔 선택 방식(SINGLE/MULTI)뿐이었다.
 * 지금은 "이 질문을 어떤 위젯으로 받는가"를 뜻한다. 이름을 바꾸려면 엔티티·DTO·관리자
 * 화면·앱을 한꺼번에 건드려야 해서, 의미 확장은 이 주석과 아래 구분이 책임진다.
 *
 * <p>선택형과 비선택형은 **답이 붙는 자리**가 다르다:
 * <ul>
 *   <li>SINGLE · MULTI — 선택지가 있고, 답은 고른 <b>항목 행</b>을 가리킨다.</li>
 *   <li>TEXT · NUMBER · DATE · ADDRESS — 선택지가 없고, 답이 <b>질문 행 자체</b>에 붙는다.</li>
 * </ul>
 *
 * <p>ADDRESS 는 새 주소 체계를 만들지 않는다 — regions(시도→시군구) 마스터를 그대로 쓴다.
 * 고정 질문인 "희망 지역"과는 다른 축이다(그쪽은 질문지 이관 전 요청의 고정 컬럼이다).
 */
public enum OptionSelectionType {
    SINGLE,
    MULTI,
    TEXT,
    NUMBER,
    DATE,
    ADDRESS;

    /** 선택지(2단 항목)를 갖는 유형인가. */
    public boolean hasChoices() {
        return this == SINGLE || this == MULTI;
    }

    /** 답이 질문 행에 직접 붙는 유형인가. */
    public boolean isDirectAnswer() {
        return !hasChoices();
    }

    /** 한 질문에 답이 여러 개일 수 있는가(ADDRESS 다중은 input_multiple 이 따로 정한다). */
    public boolean allowsMany() {
        return this == MULTI || this == ADDRESS;
    }
}
