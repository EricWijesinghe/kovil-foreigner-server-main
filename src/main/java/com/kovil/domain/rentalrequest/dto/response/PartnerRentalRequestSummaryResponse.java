package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * ★ 잡기 전 요약 — 승인된 모든 사무소에 나간다. **이름 · 전화번호 · 자유 요청사항(requestNote)은 칸째 없다**.
 *   이름 · 전화번호는 잡은 사무소에만 간다(PO 확정 2026-10-06). 자유 요청사항을 함께 빼는 것은 설계 선택이다 — 자유 글에는
 *   연락처가 들어갈 수 있어 그대로 두면 위 확정이 뚫린다. 같은 이유로 질문지의 직접 입력 글 답(textValue)도 값만 비운다.
 *   지역 메모는 짧고 잡을지 판단하는 데 필요해 싣는다.
 *   여권 성명 · 인증 서류는 어느 모양에도 없다 — "인증됨" 하나만 준다.
 */
@Schema(description = """
        임대 요청 요약 — **잡기 전**(대기 목록 · 대기 건 상세). 승인된 모든 사무소가 본다.

        **이름 · 전화번호 · 자유 요청사항은 없다** — 잡은 사무소에만 간다(`FULL`).
        질문지의 직접 입력 글 답(`options[].textValue`)도 비어 있다. 답 행과 질문 문구는 남는다.
        """)
public record PartnerRentalRequestSummaryResponse(

        @Schema(description = "응답 모양 — 요약은 언제나 `SUMMARY`", example = "SUMMARY")
        String view,

        @Schema(description = "요청 uuid. 잡기 · 상세 경로에 넣는다.")
        String requestUuid,

        @Schema(description = "업무번호. 잡은 뒤 유학생과 통화할 때 이 번호를 부른다.", example = "RQ-261007-K3M9PQ")
        String requestNumber,

        @Schema(description = "상태 — 요약은 대기(`SUBMITTED`)다", example = "SUBMITTED")
        String status,

        @Schema(description = "희망 지역(시군구) — 질문지 이전 요청의 고정 지역. 새 요청의 지역은 질문지 답(`options[].regionCode`)에 있다")
        List<RentalRequestResponse.Region> regions,

        @Schema(description = "질문지 답 — 제출 당시 문구의 스냅샷(`questionText` · `answerText` 는 한국어). 글 답(`textValue`)은 비운다")
        List<RentalRequestResponse.Option> options,

        @Schema(description = "입주 희망일", example = "2026-11-01")
        LocalDate moveInDate,

        @Schema(description = "날짜 조정 가능 여부", example = "true")
        boolean moveInFlexible,

        @Schema(description = "희망 계약 기간(개월). null = 미정", example = "12")
        Short leaseMonths,

        @Schema(description = "보증금 하한(원). null = 하한 없음", example = "5000000")
        Long depositMin,

        @Schema(description = "보증금 상한(원). null = 상한 없음", example = "20000000")
        Long depositMax,

        @Schema(description = "월세 하한(원)", example = "500000")
        Long monthlyRentMin,

        @Schema(description = "월세 상한(원)", example = "900000")
        Long monthlyRentMax,

        @Schema(description = "거주 인원", example = "1")
        Byte householdSize,

        @Schema(description = "지역 메모", example = "건대입구역 도보 10분")
        String preferredAreaNote,

        @Schema(description = "신청자의 선호 언어(언어 코드) — 통화할 언어를 가늠한다", example = "vi")
        String preferredLanguage,

        @Schema(description = """
                인증됨 — 이 요청을 통과시킨 외국인 인증이 **지금도** 유효한가(승인 상태 · 만료 전).
                요청을 낸 뒤 인증이 만료되거나 무효화되면 false 다.
                """, example = "true")
        boolean verified,

        @Schema(description = "제출 시각")
        LocalDateTime submittedAt
) implements PartnerRentalRequestView {

    public static final String VIEW = "SUMMARY";
}
