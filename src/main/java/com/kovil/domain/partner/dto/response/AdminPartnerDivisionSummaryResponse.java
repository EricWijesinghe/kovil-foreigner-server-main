package com.kovil.domain.partner.dto.response;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "파트너 부문 심사 대기열의 한 줄(관리자)")
public record AdminPartnerDivisionSummaryResponse(
        String divisionUuid,
        PartnerDivisionType division,
        PartnerDivisionStatus status,
        @Schema(description = "제출 시각 — 대기열은 이 순서다") LocalDateTime submittedAt,
        @Schema(description = "마지막 심사 시각") LocalDateTime reviewedAt,
        @Schema(description = "마지막 승인 · 복구 시각") LocalDateTime lastApprovedAt,
        @Schema(description = "승인 · 반려 요청에 그대로 싣는다. 그사이 바뀌었으면 `PARTNER_DIVISION_CHANGED`(409)") Long version,
        PartnerView partner,
        @Schema(description = "공인중개사 부문의 사무소. 대표자가 탈퇴해 연결이 끊겼으면 null") OfficeSummary office,
        @Schema(description = """
                이 사무소의 중개등록번호 또는 이 사무소 자신이 정지된 적이 있는가 — 목록의 경고 표시
                ("정지 이력이 있는 중개등록번호입니다"). 막지 않는다. 자세한 이력은 상세의 `regNoSuspensionHistory`. 사무소가 없으면 false.
                """) boolean regNoSuspendedBefore
) {

    public record PartnerView(
            String userUuid,
            @Schema(description = "가입자(대표자) 이름") String name,
            String email,
            @Schema(description = "계정 상태. `WITHDRAWN` 이면 승인할 수 없다(`PARTNER_NOT_ACTIVE`)") UserStatus accountStatus
    ) {
        public static PartnerView of(User user) {
            return user == null ? null
                    : new PartnerView(user.getUuid(), user.getName(), user.getEmail(), user.getStatus());
        }
    }

    public record OfficeSummary(String officeUuid, String regNo, String name, String ceoName) {
        public static OfficeSummary of(BrokerageOffice office) {
            return office == null ? null
                    : new OfficeSummary(office.getUuid(), office.getRegNo(), office.getName(), office.getCeoName());
        }
    }

    public static AdminPartnerDivisionSummaryResponse of(PartnerDivision d, User user, BrokerageOffice office,
                                                         boolean regNoSuspendedBefore) {
        return new AdminPartnerDivisionSummaryResponse(d.getUuid(), d.getDivision(), d.getStatus(),
                d.getSubmittedAt(), d.getReviewedAt(), d.getLastApprovedAt(), d.getVersion(),
                PartnerView.of(user), OfficeSummary.of(office), regNoSuspendedBefore);
    }
}
