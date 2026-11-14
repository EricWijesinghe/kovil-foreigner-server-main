package com.kovil.domain.rentalrequest.enums;

// SUBMITTED(대기) → IN_PROGRESS(사무소가 잡음) → CLOSED(사무소가 종료) | CANCELED(유학생이 접음).
// IN_PROGRESS → SUBMITTED 로 되돌아가는 길이 있다 — 맡은 사무소가 놓거나, 맡을 사람이 사라질 때(대표자 탈퇴 · 정지).
// CANCELED 와 CLOSED 를 하나로 합치면 "누가 접었는지"가 사라진다.
//
// ★ 이름이 곧 저장값이다(rental_requests.status ENUM). 상수를 지우려면 그 값의 행부터 옮겨야 한다 —
//   남은 행이 있으면 읽는 순간 매핑이 터진다(계약됨을 지울 때 V28 이 그렇게 했다).
public enum RentalRequestStatus {

    SUBMITTED,
    IN_PROGRESS,
    CANCELED,
    CLOSED;

    // 유학생이 손댈 수 있는 구간. 사무소가 잡은 뒤에도 고치거나 취소할 수 있다 — 사무소에 알리지는 않는다(목록에서 보인다).
    public boolean isUserEditable() {
        return this == SUBMITTED || this == IN_PROGRESS;
    }

    // 살아 있는 요청 — 유학생 한 명의 동시 요청 상한과 질문지 구조 잠금이 세는 축. 지금은 위와 같은 구간이지만 뜻이 다르다.
    public boolean isOpen() {
        return this == SUBMITTED || this == IN_PROGRESS;
    }
}
