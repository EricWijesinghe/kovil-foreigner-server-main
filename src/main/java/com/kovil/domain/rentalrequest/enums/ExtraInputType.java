package com.kovil.domain.rentalrequest.enums;

/**
 * 선택지를 고르면 열리는 추가 입력의 종류.
 *
 * <p>"기타 → 직접 입력", "원하는 날짜가 있어요 → 날짜"처럼 **선택 하나로 끝나지 않는 답**을
 * 받기 위한 축이다. 항목(2단) 행에만 의미가 있고, 그룹 행은 언제나 {@link #NONE}이다.
 *
 * <p>값을 늘리면 앱에 렌더러를 함께 붙여야 한다 — 앱이 모르는 종류는 입력칸 없이
 * 선택만 되는 것으로 취급한다(화면이 뜨는 쪽으로 기운다).
 */
public enum ExtraInputType {
    NONE,
    TEXT,
    NUMBER,
    DATE
}
