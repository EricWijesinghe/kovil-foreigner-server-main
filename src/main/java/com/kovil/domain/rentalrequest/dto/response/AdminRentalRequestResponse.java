package com.kovil.domain.rentalrequest.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * 관리자 열람(읽기 전용)의 임대 요청 1건 — 목록과 상세가 같은 모양이다.
 * ★ 전화번호는 어느 쪽 것도 싣지 않는다. 신청자 번호는 회원 화면의 열람 기록이 남는 경로로만 연다 —
 *   여기 실으면 목록을 여는 것만으로 평문이 흘러 그 기록이 무의미해진다. 유학생 응답(RentalRequestResponse)을
 *   감싸지 않는 이유도 같다(거기에는 맡은 사무소 대표자의 휴대폰이 실린다).
 * ★ 유학생 화면과 달리 종료 결과(outcome)를 싣는다 — 성사율을 보는 자리다.
 * ★ 잡기 이력(claimHistory)은 **상세에만** 싣는다 — 목록 줄에는 칸째 없다(행마다 이력을 읽으면 왕복이 페이지 크기만큼 는다).
 *   관리자에게만 보이는 칸이다. 파트너 · 유학생 응답에는 이 칸도, 잡았다 놓은 다른 사무소의 흔적도 없다.
 */
@Schema(description = """
        임대 요청 1건(관리자 열람 — 읽기 전용).

        요청 본문(지역 · 질문지 답 · 조건 · 자유 요청사항)에 **신청자(uuid · 이름 · 계정 상태)** 와
        **맡은 사무소(uuid · 상호 · 등록번호)** · 잡은 시각 · 종료 결과 · 종료 시각이 붙는다.

        **전화번호는 싣지 않는다** — 신청자 번호는 회원 상세의 번호 열람(`POST /api/v1/admin/users/{userUuid}/phone-reveal`,
        열람 기록이 남는다)으로 본다.

        상세(`GET /api/v1/admin/rental-requests/{requestUuid}`)에만 **잡기 이력(`claimHistory`)** 이 붙는다 — 목록 줄에는 그 칸이 없다.
        """)
public record AdminRentalRequestResponse(

        @Schema(description = "요청 uuid")
        String requestUuid,

        @Schema(description = "업무번호", example = "RQ-261007-K3M9PQ")
        String requestNumber,

        @Schema(description = """
                - `SUBMITTED` 대기 — 아무 사무소도 맡지 않았다(놓았거나 맡을 사람이 사라져 돌아온 건 포함)
                - `IN_PROGRESS` 진행 중 — `office` 가 맡았다
                - `CLOSED` 종료 — 맡은 사무소가 결과(`outcome`)를 골라 접었다
                - `CANCELED` 취소 — 유학생이 접었다(맡은 뒤 취소면 `office` 가 남아 있다)
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

        @Schema(description = "자유 요청사항(원문 그대로)")
        String requestNote,

        @Schema(description = "취소 사유(유학생이 적은 것). 사무소의 종료에는 사유가 없다 — 이전 방식으로 접힌 옛 종료 건만 남아 있다")
        String closedReason,

        @Schema(description = "제출 시각")
        LocalDateTime submittedAt,

        @Schema(description = "맡은 사무소가 잡은 시각. 대기(아무도 맡지 않음)면 null")
        LocalDateTime claimedAt,

        @Schema(description = "종료 · 취소 시각. 대기 · 진행 중이면 null")
        LocalDateTime closedAt,

        @Schema(description = """
                종료 결과 — `FOUND`(방을 구함) · `NOT_PROCEEDED`(진행 안 함). 종료(`CLOSED`)일 때만 있다.
                유학생 화면에는 나가지 않는다.
                """, example = "FOUND")
        String outcome,

        @Schema(description = "신청자")
        Applicant applicant,

        @Schema(description = "맡은 사무소. 아무도 맡지 않았으면(대기) null — 놓았거나 대기로 돌아간 건은 비워진다")
        Office office,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = """
                잡기 이력 — 사무소가 잡고 · 놓고 · 종료하고, 맡을 사람이 사라져 대기로 돌아간 순간들(일어난 순서대로).
                "이 요청을 잡았다 놓은 사무소"를 보는 자리다. 유학생의 수정 · 취소는 여기 없다(`status` · `closedAt` 으로 본다).

                **상세에만 있다** — 목록 줄에는 이 칸이 없다. 잡힌 적이 없으면 빈 배열이다.
                """)
        List<ClaimEvent> claimHistory
) {

    // 상세 — 목록 한 줄(잡기 이력이 null 이라 직렬화에서 칸째 빠진다)에 이력을 붙인다.
    public AdminRentalRequestResponse withHistory(List<ClaimEvent> history) {
        return new AdminRentalRequestResponse(requestUuid, requestNumber, status, regions, options,
                moveInDate, moveInFlexible, leaseMonths, depositMin, depositMax, monthlyRentMin, monthlyRentMax,
                householdSize, preferredAreaNote, requestNote, closedReason, submittedAt, claimedAt, closedAt,
                outcome, applicant, office, history);
    }

    // 문서의 스키마 이름은 단순 클래스 이름이라, 이름을 주지 않으면 파트너 응답의 Applicant(휴대폰 칸이 있다)와
    // 한 스키마로 합쳐져 관리자 응답에 전화 칸이 있는 것처럼 문서가 나간다. 아래 Office 도 유학생 응답의 Office 와 같은 이유다.
    @Schema(name = "AdminRentalRequestApplicant", description = "신청자 — 전화번호는 싣지 않는다(회원 상세의 번호 열람으로 본다)")
    public record Applicant(
            @Schema(description = "회원 uuid — 회원 상세 · 번호 열람의 경로에 넣는다") String userUuid,
            @Schema(description = "이름(회원 정보). 입력하지 않은 회원 · 탈퇴한 회원은 null", example = "홍길동") String name,
            @Schema(description = "계정 상태 — `ACTIVE` · `SUSPENDED` · `WITHDRAWN`. 정지 · 탈퇴한 신청자의 대기 건은 사무소 목록에서 빠진다",
                    example = "ACTIVE") UserStatus accountStatus) {

        public static Applicant of(User user) {
            return user == null ? null : new Applicant(user.getUuid(), user.getName(), user.getStatus());
        }
    }

    @Schema(name = "AdminRentalRequestOffice", description = "맡은 중개사무소")
    public record Office(
            @Schema(description = "사무소 uuid — 목록의 `officeUuid` 필터에 넣는다") String officeUuid,
            @Schema(description = "상호", example = "행복공인중개사사무소") String name,
            @Schema(description = "중개등록번호(지금 값)", example = "99999-2026-00001") String regNo) {

        public static Office of(BrokerageOffice office) {
            return office == null ? null : new Office(office.getUuid(), office.getName(), office.getRegNo());
        }
    }

    @Schema(name = "AdminRentalRequestClaimEvent", description = "잡기 이력 한 줄")
    public record ClaimEvent(
            @Schema(description = """
                    - `CLAIMED` 잡음 — 그 사무소가 맡았다
                    - `RELEASED` 놓음 — 맡은 사무소가 스스로 놓아 대기로 돌아갔다
                    - `RETURNED` 대기 복귀 — 맡을 사람이 사라져 대기로 돌아갔다(원인은 `returnCause`)
                    - `CLOSED` 종료 — 맡은 사무소가 결과(`outcome`)를 골라 접었다
                    """, example = "RELEASED") String type,
            @Schema(description = "그 순간 요청을 쥐고 있던 사무소 uuid — 목록의 `officeUuid` 필터에 넣는다") String officeUuid,
            @Schema(description = "그 사무소의 지금 상호", example = "행복공인중개사사무소") String officeName,
            @Schema(description = "일어난 시각") LocalDateTime occurredAt,
            @Schema(description = """
                    대기 복귀(`RETURNED`)의 원인 — `REPRESENTATIVE_WITHDRAWN`(대표자 탈퇴) · `DIVISION_SUSPENDED`(부문 정지) ·
                    `ACCOUNT_SUSPENDED`(대표자 계정 정지). 다른 사건이면 null.
                    """, example = "DIVISION_SUSPENDED") String returnCause,
            @Schema(description = "종료(`CLOSED`)의 결과 — `FOUND` · `NOT_PROCEEDED`. 다른 사건이면 null", example = "FOUND")
            String outcome) {

        public static ClaimEvent of(RentalRequestClaimEvent event, BrokerageOffice office) {
            return new ClaimEvent(event.getEventType().name(),
                    office == null ? null : office.getUuid(),
                    office == null ? null : office.getName(),
                    event.getOccurredAt(),
                    event.getReturnCause() == null ? null : event.getReturnCause().name(),
                    event.getOutcome() == null ? null : event.getOutcome().name());
        }
    }
}
