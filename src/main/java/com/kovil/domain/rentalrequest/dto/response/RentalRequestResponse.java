package com.kovil.domain.rentalrequest.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        임대 요청 1건(유학생 본인용).

        **맡은 사무소(`office`)** 는 사무소가 잡은 뒤에 실린다 — 상호 · 대표자명 · 소재지 · 대표번호는 계속 보이고,
        **대표자 휴대폰은 진행 중(`IN_PROGRESS`)일 때만** 실린다(종료 · 취소 · 대기로 돌아가면 가린다).
        종료 결과(방을 구했는지)는 이 응답에 없다 — 유학생 화면은 "종료"만 그린다.

        선택 항목은 **저장 당시 고른 것 그대로** 내려간다. 옵션이 나중에 비활성화돼도
        이미 낸 요청에서는 계속 보인다 — 유저가 무엇을 골랐는지가 바뀌면 안 되기 때문이다.
        """)
public record RentalRequestResponse(

        @Schema(description = "요청 uuid. 수정·취소 시 경로에 넣는다.")
        String requestUuid,

        @Schema(description = """
                업무번호. **맡은 사무소가 전화 · 문자로 이 번호를 부른다** — 화면에 크게 노출할 것.
                """, example = "RQ-260811-K3M9PQ")
        String requestNumber,

        @Schema(description = """
                - `SUBMITTED` 대기 — 승인된 중개사무소들이 보는 목록에 올라 있다. 아직 아무도 맡지 않았다
                - `IN_PROGRESS` 진행 중 — 한 사무소가 맡았다(`office`). 서로 전화번호로 연락한다
                - `CLOSED` 종료 — 맡은 사무소가 접었다. 새 요청을 낼 수 있다
                - `CANCELED` 취소 — 유학생이 접었다

                진행 중이던 요청이 **다시 대기로 돌아갈 수 있다** — 맡은 사무소가 놓았거나 더는 맡을 수 없게 됐을 때
                (`RENTAL_REQUEST_RELEASED` 알림). 그때 `office` 는 비워진다.
                """, example = "SUBMITTED")
        String status,

        @Schema(description = """
                **수정·취소 버튼을 그릴지의 판단값.** FE가 상태 전이 규칙을 알 필요가 없다.
                """, example = "true")
        boolean editable,

        @Schema(description = "희망 지역(시군구)")
        List<Region> regions,

        @Schema(description = "선택 옵션")
        List<Option> options,

        @Schema(description = "입주 희망일", example = "2026-10-01")
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

        @Schema(description = "거주 인원", example = "2")
        Byte householdSize,

        @Schema(description = "지역 자유 메모", example = "건대입구역 도보 10분")
        String preferredAreaNote,

        @Schema(description = "자유 요청사항(원문 그대로)")
        String requestNote,

        @Schema(description = """
                취소 사유(유학생이 적은 것). 사무소의 종료에는 사유가 없다 — 이전 방식으로 접힌 옛 종료 건만 사유가 남아 있다.
                """)
        String closedReason,

        @Schema(description = "제출 시각")
        LocalDateTime submittedAt,

        @Schema(description = "종료 · 취소 시각. 대기 · 진행 중이면 null")
        LocalDateTime closedAt,

        @Schema(description = "사무소가 맡은 시각. 아무도 맡지 않았으면(대기) null")
        LocalDateTime claimedAt,

        @Schema(description = """
                맡았던 사무소가 마지막으로 이 요청을 놓은 시각 — 사무소가 스스로 놓았거나, 그 사무소가 더는 맡을 수 없게 돼
                (대표자 탈퇴 · 정지) 대기로 돌아간 때. 그런 일이 없었으면 null.
                `status=SUBMITTED` 이고 이 값이 있으면 "맡았던 사무소가 요청을 놓았어요 — 다른 사무소를 기다리는 중" 을 그린다.
                다시 다른 사무소가 맡아도 값은 남는다(그때는 `status` 가 `IN_PROGRESS` 라 그리지 않는다). 어느 사무소인지 · 왜인지는 싣지 않는다.
                """)
        LocalDateTime lastReleasedAt,

        @Schema(description = "맡은 사무소. 아무도 맡지 않았으면(대기) null")
        Office office
) {

    /*
     * ★ 대표자 휴대폰(ceoPhone)은 **그 요청을 낸 유학생에게만** 내보내는 값이다. 다른 화면(파트너 · 관리자)이 이 응답을
     *   통째로 감싸 내보내면 그 번호가 함께 나간다 — 관리자는 전화번호를 열람 기록이 남는 경로로만 본다.
     */
    @Schema(description = """
            맡은 중개사무소. 상호 · 대표자명 · 소재지 · 대표번호는 광고에도 나가는 공개 정보라 종료 · 취소 뒤에도 보인다.
            **대표자 휴대폰은 진행 중일 때만** 실린다 — 문자 연락용이다.
            """)
    public record Office(
            @Schema(description = "상호", example = "행복공인중개사사무소") String name,
            @Schema(description = "대표자 성명", example = "홍길동") String ceoName,
            @Schema(description = "소재지", example = "서울특별시 광진구 능동로 000") String address,
            @Schema(description = "대표번호", example = "02-000-0000") String tel,
            @Schema(description = """
                    대표자 휴대폰. **진행 중(`IN_PROGRESS`)일 때만** 실리고 그 밖에는 null 이다.
                    읽을 수 없으면(대표자가 떠났거나 계정이 정지됨 · 복호화 실패) 진행 중이어도 null 이다 — 대표번호로 연락한다.
                    """, example = "010-0000-0000") String ceoPhone) {
    }

    @Schema(description = "선택한 시군구")
    public record Region(
            @Schema(description = "법정동코드 5자리", example = "11215") String code,
            @Schema(description = "표시명", example = "광진구") String name) {
    }

    @Schema(description = """
            요청자가 고른 답 하나.

            ★ 표시는 전부 **제출 당시 문구의 스냅샷**이다 — 마스터가 그 뒤에 바뀌어도
            이 답의 표시는 변하지 않는다(제출된 요청은 그 시점의 박제본, V13).
            `questionText` · `answerText`는 운영 언어(ko — 중개사무소가 읽는다) 스냅샷이고, `name`은 화면 언어에
            맞는 스냅샷(ko 화면은 ko, 그 외에는 제출자가 본 언어)이다. 직접 입력 답은
            `name`이 질문 문구이고 값은 `textValue`·`numberValue`·`dateValue`로 나간다.
            """)
    public record Option(
            @Schema(description = "속한 그룹 code", example = "ROOM_TYPE") String groupCode,
            @Schema(description = "항목 code", example = "ONE_ROOM") String code,
            @Schema(description = "표시명 — 제출 당시 스냅샷(화면 언어 기준)", example = "원룸") String name,

            @Schema(description = """
                    **이 답이 걸린 질문의 문구**(화면 언어 스냅샷) — 상세 화면의 행 라벨.
                    선택지 답이면 부모 질문, 직접 입력 답이면 질문 자신의 문구다.
                    """, example = "어떤 집을 찾으세요?") String questionName,

            @Schema(description = """
                    **답의 문구**(화면 언어 스냅샷) — 선택지 라벨·지역명. 직접 입력 답
                    (TEXT·NUMBER·DATE)은 값 컬럼이 정본이라 `null`이다.
                    """, example = "원룸") String answerName,

            @Schema(description = "제출 당시 질문 문구(ko 스냅샷)", example = "어떤 집을 찾으세요?")
            String questionText,
            @Schema(description = "제출 당시 선택지 문구(ko 스냅샷)", example = "원룸")
            String answerText,

            @Schema(description = """
                    항목이 연 입력칸에 적은 값(TEXT). 파트너의 잡기 전 요약(`SUMMARY`)에서는 늘 비어 있다 —
                    자유 글이라 연락처가 들어갈 수 있다.
                    """) String textValue,
            @Schema(description = "항목이 연 입력칸에 적은 값(NUMBER)") Long numberValue,
            @Schema(description = "항목이 연 입력칸에 적은 값(DATE)") LocalDate dateValue,
            @Schema(description = """
                    ADDRESS 답이 고른 시군구 code — **수정 화면의 기계 축**이다. 표시는 스냅샷
                    (`answerText`)이 담당하지만, 수정(PUT)은 통째 덮어쓰기라 code 가 없으면
                    앱이 기존 답을 되돌려보낼 수 없어 저장 한 번에 ADDRESS 답이 소실된다.
                    """, example = "11215") String regionCode) {
    }
}
