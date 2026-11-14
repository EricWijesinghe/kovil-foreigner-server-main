package com.kovil.domain.partner.dto.response;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "사무소 정보 변경 심사 대기열의 한 줄(관리자)")
public record AdminOfficeChangeRequestSummaryResponse(
        String requestUuid,
        OfficeChangeRequestStatus status,
        @Schema(description = "마지막으로 낸 시각 — 대기열은 이 순서다") LocalDateTime submittedAt,
        @Schema(description = "승인 · 반려 시각") LocalDateTime reviewedAt,
        @Schema(description = "승인 · 반려 요청에 그대로 싣는다. 그사이 바뀌었으면 `OFFICE_CHANGE_REQUEST_CHANGED`(409)") Long version,
        AdminPartnerDivisionSummaryResponse.PartnerView partner,
        @Schema(description = "사무소의 **지금** 값(등록번호 · 상호 · 대표자)") AdminPartnerDivisionSummaryResponse.OfficeSummary office,
        @Schema(description = "바꿀 중개등록번호") String requestedRegNo,
        @Schema(description = "바꿀 상호") String requestedName,
        @Schema(description = "바꿀 번호가 사무소의 지금 번호와 다른가 — 대기 건이면 승인 때 번호가 옮겨진다(승인된 건은 이미 그 번호라 false)") boolean regNoChanged,
        @Schema(description = """
                **바꿀 번호 · 사무소의 지금 번호 · 이 사무소 자신** 중 하나라도 정지된 적이 있는가 — 목록의 경고 표시
                ("정지 이력이 있는 중개등록번호입니다"). 막지 않는다. 정지됐던 사무소가 번호를 바꾼 뒤 낸 신청도 켜진다.
                """) boolean regNoSuspendedBefore
) {

    public static AdminOfficeChangeRequestSummaryResponse of(BrokerageOfficeChangeRequest r, User partner,
                                                             BrokerageOffice office, boolean regNoSuspendedBefore) {
        return new AdminOfficeChangeRequestSummaryResponse(r.getUuid(), r.getStatus(), r.getSubmittedAt(),
                r.getReviewedAt(), r.getVersion(), AdminPartnerDivisionSummaryResponse.PartnerView.of(partner),
                AdminPartnerDivisionSummaryResponse.OfficeSummary.of(office), r.getRegNo(), r.getName(),
                office != null && !office.getRegNo().equals(r.getRegNo()), regNoSuspendedBefore);
    }
}
