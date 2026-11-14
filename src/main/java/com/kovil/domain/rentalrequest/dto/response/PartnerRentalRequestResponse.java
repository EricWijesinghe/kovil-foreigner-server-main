package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


@Schema(description = """
        임대 요청 전체 — **내 사무소가 맡은 요청**(진행 중 · 종료 · 맡은 뒤 유학생이 취소한 것).

        요약 칸에 더해 신청자 이름 · **휴대폰(진행 중일 때만)** · 자유 요청사항이 실린다.
        """)
public record PartnerRentalRequestResponse(

        @Schema(description = "응답 모양 — 전체는 언제나 `FULL`", example = "FULL")
        String view,

        @Schema(description = "요청 uuid")
        String requestUuid,

        @Schema(description = "업무번호", example = "RQ-261007-K3M9PQ")
        String requestNumber,

        @Schema(description = """
                - `IN_PROGRESS` 진행 중 — 내 사무소가 맡았다. 휴대폰이 실린다
                - `CLOSED` 종료 — 내 사무소가 결과를 골라 접었다
                - `CANCELED` 취소 — 맡은 뒤 유학생이 접었다(사무소에 알림은 가지 않는다)
                """, example = "IN_PROGRESS")
        String status,

        @Schema(description = "희망 지역(시군구) — 질문지 이전 요청의 고정 지역")
        List<RentalRequestResponse.Region> regions,

        @Schema(description = "질문지 답 — 제출 당시 문구의 스냅샷")
        List<RentalRequestResponse.Option> options,

        @Schema(description = "입주 희망일", example = "2026-11-01")
        LocalDate moveInDate,

        @Schema(description = "날짜 조정 가능 여부", example = "true")
        boolean moveInFlexible,

        @Schema(description = "희망 계약 기간(개월). null = 미정", example = "12")
        Short leaseMonths,

        @Schema(description = "보증금 하한(원)", example = "5000000")
        Long depositMin,

        @Schema(description = "보증금 상한(원)", example = "20000000")
        Long depositMax,

        @Schema(description = "월세 하한(원)", example = "500000")
        Long monthlyRentMin,

        @Schema(description = "월세 상한(원)", example = "900000")
        Long monthlyRentMax,

        @Schema(description = "거주 인원", example = "1")
        Byte householdSize,

        @Schema(description = "지역 메모", example = "건대입구역 도보 10분")
        String preferredAreaNote,

        @Schema(description = "신청자의 선호 언어(언어 코드)", example = "vi")
        String preferredLanguage,

        @Schema(description = "인증됨 — 이 요청을 통과시킨 외국인 인증이 지금도 유효한가", example = "true")
        boolean verified,

        @Schema(description = "제출 시각")
        LocalDateTime submittedAt,

        @Schema(description = "내 사무소가 잡은 시각")
        LocalDateTime claimedAt,

        @Schema(description = "종료 · 취소 시각. 진행 중이면 null")
        LocalDateTime closedAt,

        @Schema(description = "종료 결과 — `FOUND`(방을 구함) · `NOT_PROCEEDED`(진행 안 함). 종료(`CLOSED`)일 때만 있다",
                example = "FOUND")
        String outcome,

        @Schema(description = "자유 요청사항(원문 그대로) — 잡은 사무소에만 간다")
        String requestNote,

        @Schema(description = "신청자 연락처")
        Applicant applicant
) implements PartnerRentalRequestView {

    public static final String VIEW = "FULL";

    @Schema(description = "신청자. 여권 성명 · 인증 서류는 주지 않는다.")
    public record Applicant(
            @Schema(description = "이름(회원 정보). 입력하지 않은 회원 · 탈퇴한 회원은 null", example = "홍길동")
            String name,

            @Schema(description = """
                    휴대폰. **진행 중(`IN_PROGRESS`)일 때만** 실린다 — 종료 · 취소면 null.
                    신청자 계정이 정지됐거나 읽을 수 없으면 진행 중이어도 null 이다.
                    """, example = "010-0000-0000")
            String phone) {
    }
}
