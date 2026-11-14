package com.kovil.domain.partner.dto.response;

import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import io.swagger.v3.oas.annotations.media.Schema;

// 변경 신청이 담은 "바꿀 값" 한 벌. 대표번호는 없다 — 심사 없이 바로 바뀌는 칸이라 변경 신청에 실리지 않는다.
@Schema(description = "변경 신청의 바꿀 값 한 벌(대표번호 제외). 승인되면 이 값이 사무소에 적용된다.")
public record OfficeChangeValues(
        String regNo,
        String name,
        String ceoName,
        String address,
        String bizNo,
        @Schema(description = "심사받을 등록증 파일 uuid. 열람은 `GET /api/v1/files/{fileUuid}/url`") String licenseFileUuid
) {
    public static OfficeChangeValues of(BrokerageOfficeChangeRequest request, String licenseFileUuid) {
        return new OfficeChangeValues(request.getRegNo(), request.getName(), request.getCeoName(),
                request.getAddress(), request.getBizNo(), licenseFileUuid);
    }
}
