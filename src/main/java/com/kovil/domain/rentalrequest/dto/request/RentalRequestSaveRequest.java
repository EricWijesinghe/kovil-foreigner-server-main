package com.kovil.domain.rentalrequest.dto.request;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import com.kovil.common.validator.annotation.RangeWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = """
        임대 요청 등록·수정 본문. **등록(POST)과 수정(PUT)이 같은 모양**이다 —
        수정 화면은 등록 화면과 같은 폼이므로 부분 패치가 아니라 **통째로 덮어쓴다.**

        보내지 않은 선택 항목은 **비워진다.** 지역 3곳을 골랐다가 2곳만 보내면 1곳이 빠지는 것이
        의도된 동작이다.

        **먼저 `/rental-requests/options`로 옵션 트리를, `/regions`로 지역 트리를 받아 그린다.**
        """)
public record RentalRequestSaveRequest(

        @Schema(description = """
                희망 지역의 **시군구 code**(5자리). 시도 code(2자리)를 보내면 `REGION_INVALID_LEVEL`.
                최소 1개는 필요하다.
                """, example = "[\"11215\", \"11350\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED)

        @SizeWithErrorCode(value = "REGION_REQUIRED", min = 0, max = 20)
        List<String> regionCodes,

        @Schema(description = """
                선택 옵션의 **항목 code**. 그룹 code(ROOM_TYPE 등)를 보내면 `OPTION_SELECTION_INVALID`.
                SINGLE 그룹에서 두 개를 고르면 같은 코드로 거절된다. 아무것도 안 고르면 빈 배열.
                """, example = "[\"ONE_ROOM\", \"AIR_CONDITIONER\", \"PET_ALLOWED\"]")
        List<String> optionCodes,

        @Schema(description = """
                선택한 항목이 **추가 입력칸을 여는 경우**(기타 → 직접 입력 등) 그 값들.
                선택 자체는 `optionCodes`가 소유하고 여기는 값만 얹는다 — 여기에만 있고
                `optionCodes`에 없는 코드는 선택으로 치지 않는다.

                비워 보내도 된다. 그때는 추가 입력이 필수인 항목(`extraRequired`)에서만
                `OPTION_EXTRA_REQUIRED`(400)가 난다.
                """)
        List<RequestAnswerEntry> optionAnswers,

        @Schema(description = "입주 희망일. 오늘 이전은 `MOVE_IN_DATE_IN_PAST`.",
                example = "2026-10-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDate moveInDate,

        @Schema(description = """
                **날짜 조정 가능 여부.** 매칭 폭이 크게 달라지는 축이라 화면에서 눈에 띄게 물을 것.
                """, example = "true")
        Boolean moveInFlexible,

        @Schema(description = "희망 계약 기간(개월). 비우면 미정.", example = "12")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = 120)
        Short leaseMonths,

        @Schema(description = "보증금 하한(원). 비우면 하한 없음.", example = "5000000")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 100_000_000_000L)
        Long depositMin,

        @Schema(description = "보증금 상한(원). 하한보다 작으면 `BUDGET_RANGE_INVALID`.", example = "20000000")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 100_000_000_000L)
        Long depositMax,

        @Schema(description = "월세 하한(원)", example = "500000")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 100_000_000_000L)
        Long monthlyRentMin,

        @Schema(description = "월세 상한(원)", example = "900000")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 100_000_000_000L)
        Long monthlyRentMax,

        @Schema(description = "거주 인원. 전대차 매물 매칭의 실질 제약", example = "2")
        @RangeWithErrorCode(value = "INVALID_REQUEST", min = 1, max = 20)
        Byte householdSize,

        @Schema(description = """
                시군구만으로는 안 잡히는 실수요. 역·학교·직장 기준으로 적게 한다.
                """, example = "건대입구역 도보 10분")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 255)
        String preferredAreaNote,

        @Schema(description = """
                자유 요청사항. **유저 원문 그대로 저장되고 번역하지 않는다** — 맡은 사무소가 원문을 본다.
                **잡기 전의 사무소들에게는 보이지 않는다**(연락처가 들어 있을 수 있다) — 잡은 사무소에만 나간다.
                """, example = "I need a place close to Konkuk University. Pet friendly if possible.")
        @SizeWithErrorCode(value = "INVALID_REQUEST", min = 0, max = 2000)
        String requestNote
) {
}
