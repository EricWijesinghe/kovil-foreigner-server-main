package com.kovil.domain.partner.dto.response;

import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        사무소 정보 변경 신청 상세(관리자). **지금 값(`current`)과 바꿀 값(`requested`)을 나란히** 보고, 새 등록증으로 바꿀 값을
        확인해 승인한다. 심사 동안 그 사무소의 부문 · 매물은 그대로다.
        """)
public record AdminOfficeChangeRequestDetailResponse(
        String requestUuid,
        OfficeChangeRequestStatus status,
        LocalDateTime submittedAt,
        @Schema(description = "반려 사유(반려된 건)") String rejectReason,
        LocalDateTime reviewedAt,
        @Schema(description = "승인 · 반려 요청에 그대로 싣는다. 그사이 바뀌었으면 `OFFICE_CHANGE_REQUEST_CHANGED`(409)") Long version,
        @Schema(description = "신청한 파트너(사무소 대표자)") AdminPartnerDivisionSummaryResponse.PartnerView partner,
        @Schema(description = "그 사람의 공인중개사 부문 상태. `APPROVED` 가 아니면 승인이 `OFFICE_CHANGE_DIVISION_NOT_APPROVED`(409)")
        PartnerDivisionStatus divisionStatus,
        @Schema(description = "사무소의 지금 값(지금 등록증 uuid 포함). 대표번호는 변경 신청 대상이 아니다") PartnerMeResponse.OfficeView current,
        @Schema(description = "바꿀 값 한 벌") OfficeChangeValues requested,
        @Schema(description = """
                바꿀 값의 등록증 단기 열람 URL. **캐시하거나 공유하지 않는다.** 만료되면 상세를 다시 받는다.
                폐기됐으면(반려 · 탈퇴로 닫힌 건) null.
                """) FileReadUrlResponse license,
        @Schema(description = """
                **바꿀 번호**의 다른 신청 — 그 번호로 낸 부문 신청(첫 심사 대기 · 반려) · 그 번호를 잡고 있는 사무소(`claimed=true`) ·
                그 번호로 옮기려는 다른 사무소의 대기 중 변경 신청. 부문 심사 상세의 `sameRegNoApplications` 와 같은 모양이고 `kind` 로 가른다.
                `claimed=true` 인 줄이 있으면 이 변경 신청의 승인이 `OFFICE_REG_NO_DUPLICATED`(409)로 막힌다. 이 사무소 자신은 싣지 않는다.
                """) List<AdminPartnerDivisionDetailResponse.SameRegNoApplication> sameRegNoApplications,
        @Schema(description = """
                정지 이력(최근 정지 먼저) — **바꿀 번호 · 사무소의 지금 번호**로 정지된 기록과 **이 사무소 자신**의 정지 기록(그때 번호가
                지금과 달라도)이 함께, 겹친 기록은 한 번만 실린다. 비어 있지 않으면 **"정지 이력이 있는 중개등록번호입니다"** 경고를 띄운다.
                각 줄의 `officeUuid` 가 이 사무소면 이 사무소 자신의 정지다(`regNo` 는 그때 번호). 승인을 막지 않는다 — 관리자가 판단한다.
                """) List<RegNoSuspensionView> regNoSuspensionHistory
) {

    public static AdminOfficeChangeRequestDetailResponse of(BrokerageOfficeChangeRequest r,
                                                            AdminPartnerDivisionSummaryResponse.PartnerView partner,
                                                            PartnerDivisionStatus divisionStatus,
                                                            PartnerMeResponse.OfficeView current,
                                                            OfficeChangeValues requested,
                                                            FileReadUrlResponse license,
                                                            List<AdminPartnerDivisionDetailResponse.SameRegNoApplication> sameRegNoApplications,
                                                            List<RegNoSuspensionView> regNoSuspensionHistory) {
        return new AdminOfficeChangeRequestDetailResponse(r.getUuid(), r.getStatus(), r.getSubmittedAt(),
                r.getRejectReason(), r.getReviewedAt(), r.getVersion(), partner, divisionStatus, current, requested,
                license, sameRegNoApplications, regNoSuspensionHistory);
    }
}
