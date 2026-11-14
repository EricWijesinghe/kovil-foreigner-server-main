package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.PatternWithErrorCode;
import com.kovil.common.validator.annotation.SizeWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

// 공인중개사 부문의 상세 — 중개사무소 정보. 가입과 제출이 같은 모양을 쓴다.
// 상호 · 대표자 · 소재지 · 대표번호는 광고의 법정 표시 항목이라 매물 화면에 그대로 나간다.
@Schema(description = "중개사무소 정보(공인중개사 부문의 상세). 매물 광고의 법정 표시 항목이 여기서 나온다.")
public record BrokerageOfficeForm(

        @Schema(description = """
                중개등록번호 '지자체5-연도4-일련5'. 같은 번호로 여러 명이 신청할 수 있고 관리자가 한 명만 승인한다.
                그 번호를 승인받은 사무소가 아직 잡고 있으면 `OFFICE_REG_NO_DUPLICATED`(409) — 승인 · 정지된 사무소(번호를 바꾸는
                변경 신청이 대기 · 반려 중이어도 옛 번호를 계속 쓴다). 대표자가 탈퇴하거나 변경 승인으로 번호를 옮기면 풀린다.
                """,
                example = "99999-2026-00002", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @PatternWithErrorCode(value = "INVALID_REQUEST", regexp = "\\d{5}-\\d{4}-\\d{5}")
        String regNo,

        @Schema(description = "상호(사무소명)", example = "테스트공인중개사사무소",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 100)
        String name,

        @Schema(description = "대표자(개업공인중개사) 성명. 등록증의 성명과 같아야 심사를 통과한다.",
                example = "홍길동", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 50)
        String ceoName,

        @Schema(description = "소재지", example = "서울특별시 관악구",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 255)
        String address,

        @Schema(description = "대표번호(공시 연락처)", example = "02-000-0000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("INVALID_REQUEST")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 30)
        String tel,

        @Schema(description = "사업자등록번호(선택)", example = "000-00-00000")
        @SizeWithErrorCode(value = "INVALID_REQUEST", max = 12)
        String bizNo
) {
}
