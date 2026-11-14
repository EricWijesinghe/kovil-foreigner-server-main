package com.kovil.domain.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 카탈로그. **문구는 여기 없다** — 메시지 번들이 소유한다(FR-603).
// template_code를 타입에서 파생시키면 타입 추가 시 번들 키를 잊을 수 없다.
@Getter
@RequiredArgsConstructor
public enum NotiType {

    // 관리자 파이프라인 점검용 — 자기 자신에게만 발송된다.
    PUSH_TEST(NotiCategory.CONTRACT),
    VERIFICATION_APPROVED(NotiCategory.CONTRACT),
    VERIFICATION_REJECTED(NotiCategory.CONTRACT),
    VERIFICATION_REVOKED(NotiCategory.CONTRACT),
    /*
     * ★ PARTNER_DIVISION_* 넷은 받는 사람이 **파트너**다 — 관리자의 부문 심사 결과. 승인 · 복구는 세션을 끊지 않으므로
     *   앱이 새 토큰을 받으러 갈 신호가 이 알림이다. 반려만 사유를 싣는다({0}) — 정지 사유는 관리자 내부 기록이다.
     */
    PARTNER_DIVISION_APPROVED(NotiCategory.CONTRACT),
    PARTNER_DIVISION_REJECTED(NotiCategory.CONTRACT),
    PARTNER_DIVISION_SUSPENDED(NotiCategory.CONTRACT),
    PARTNER_DIVISION_REINSTATED(NotiCategory.CONTRACT),
    /*
     * ★ OFFICE_CHANGE_* 둘도 받는 사람이 파트너다 — 승인된 사무소의 정보 변경 신청 결과. 반려만 사유를 싣는다({0}).
     *   반려돼도 사무소 정보 · 매물은 그대로라는 것을 문구가 알려야 한다(영업이 멈췄다고 오해하지 않게).
     */
    OFFICE_CHANGE_APPROVED(NotiCategory.CONTRACT),
    OFFICE_CHANGE_REJECTED(NotiCategory.CONTRACT),
    /*
     * ★ RENTAL_REQUEST_* 셋은 받는 사람이 **유학생**이다 — 자기 요청을 사무소가 잡음({0} 요청 번호 · {1} 사무소 상호) ·
     *   종료 · 다시 대기(놓기 · 맡을 사람이 사라짐). 같은 요청이 여러 번 잡히고 놓일 수 있어 중복 키에 처리 시각을 넣는다
     *   (RentalRequestNotifier). 유학생의 취소 · 수정은 사무소에 알리지 않는다 — 목록에서 상태로 본다.
     */
    RENTAL_REQUEST_IN_PROGRESS(NotiCategory.CONTRACT),
    RENTAL_REQUEST_CLOSED(NotiCategory.CONTRACT),
    RENTAL_REQUEST_RELEASED(NotiCategory.CONTRACT),
    /*
     * ★ LISTING_* 둘은 받는 사람이 **중개사**다(누구인지는 ListingAlertRecipients 가 정한다).
     *   연장 창(만료 + 7일)은 **이 알림이 없으면 아무도 모르는 창**이다.
     */
    LISTING_EXPIRED(NotiCategory.CONTRACT),
    // 서비스 지역이 닫혀 매물을 내렸다 — 중개사별 1건, {0} = 내려간 건수. 알리지 않으면 광고가 이유 없이 사라진 것으로 보인다.
    LISTING_REGION_CLOSED(NotiCategory.CONTRACT),

    LISTING_BLOCKED(NotiCategory.CONTRACT),

    COMMUNITY_COMMENT(NotiCategory.COMMUNITY),
    NOTICE(NotiCategory.MARKETING);

    private final NotiCategory category;

    // 번들 키는 타입 이름을 소문자로 옮긴 것이다 — 둘이 어긋날 여지를 없앤다.
    public String templateCode() {
        return name().toLowerCase();
    }
}
