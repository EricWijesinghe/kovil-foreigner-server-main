package com.kovil.domain.listing.enums;

/*
 * 공개 목록의 정렬 축.
 *
 * ★★ LATEST 의 기준은 **최초 게시(posted_at)** 다. 갱신 시각이 아니다 —
 *    갱신 기준으로 두면 중개사가 버튼 한 번으로 상단을 차지하는 **끌어올리기(bump)** 가 된다.
 *    다방이 그 상태다: 실측에서 5개월 전 매물이 55초 전 갱신으로 맨 위에 있었고,
 *    초 단위 갱신이 여러 건 관측됐다. **가장 조작하기 쉬운 축을 1위 요인으로 쓴 결과**다.
 *
 * ★ RECOMMENDED 는 v1 에서 **적합도 + 최신순**이다(PO 확정 2026-10-05).
 *   조회·찜·문의가 0인 상태에서 반응 점수는 분모가 없어 무작위가 되고,
 *   복잡한 점수는 "유학생에게 맞는 집을 먼저"라는 메시지만 흐린다.
 */
public enum ListingSort {

    RECOMMENDED,
    LATEST,
    PRICE_ASC,
    PRICE_DESC,
    AREA_DESC
}
