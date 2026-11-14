package com.kovil.common.web;

import org.springframework.data.domain.PageRequest;

/**
 * 페이지 파라미터의 유일한 관문 — **웹 계층은 Spring Data의 Pageable 바인딩을 쓰지 않는다.**
 *
 * <p>Pageable을 컨트롤러 인자로 받으면 프레임워크가 `page`·`size`·`sort` 예약어로 공개 계약에
 * 끼어든다. 그 결과가 실제로 셋 다 사고였다:
 * <ul>
 *   <li>도메인 enum `sort=LATEST`를 Pageable 정렬로도 해석해 문자열 @Query 뒤에
 *       `ORDER BY p.LATEST`가 붙어 500 (실서버에서 터진 실사고)</li>
 *   <li>클라이언트가 `?sort=body` 같은 임의 컬럼 정렬을 주입할 수 있다 — 비인덱스 TEXT 정렬은
 *       크래시가 아니라 성능 공격 표면이다</li>
 *   <li>springdoc이 pageable을 객체로 문서화해 실물(평평한 page/size)과 계약 문서가 어긋났다</li>
 * </ul>
 *
 * <p>그래서 컨트롤러는 `page`·`size`를 명시적 @RequestParam으로 받고 여기서 PageRequest를 만든다.
 * **정렬은 항상 도메인 enum 또는 쿼리의 ORDER BY**다 — 클라이언트 Sort 객체는 저장소까지
 * 절대 흘러가지 않는다. 위반은 PagingPolicyTest가 잡는다.
 */
public final class Paging {

    /** 페이지 크기 상한 — 화면 요구는 20이고, 상한은 실수·남용으로 전체 테이블을 끌어오는 것만 막는다. */
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    // 음수·0·과대값은 거부 대신 **클램프**한다 — 페이지 파라미터가 살짝 어긋났다고 화면 전체를
    // 에러로 만드는 것보다, 안전한 값으로 흡수하는 쪽이 사용자에게 낫다(선언된 설계 선택).
    public static PageRequest of(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return PageRequest.of(safePage, safeSize);
    }
}
