package com.kovil.domain.partner.dto.request;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = """
        공인중개사 부문 제출 · 재제출 · 승인 뒤 변경 신청. 사무소 정보와 등록증을 **한 벌로** 낸다 — 심사자는 지금 낸 한 벌을 본다.
        승인된 부문이 내면 변경 신청이다(부문 · 사무소는 그대로, 심사 대상이 지금 값과 같으면 `PARTNER_REVIEW_NO_CHANGE` 400,
        `office.tel` 은 쓰지 않는다).
        등록증은 먼저 `POST /api/v1/files/presign` · `/confirm` 에 `purpose=BROKER_LICENSE` 로 올린다.
        """)
public record RealEstateBrokerSubmitRequest(

        @Schema(description = "중개사무소 정보", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNullWithErrorCode("INVALID_REQUEST")
        @Valid
        BrokerageOfficeForm office,

        @Schema(description = "중개사무소 등록증 사본의 파일 uuid(본인이 `BROKER_LICENSE` 로 올린 것)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlankWithErrorCode("PARTNER_LICENSE_FILE_INVALID")
        String licenseFileUuid
) {
}
