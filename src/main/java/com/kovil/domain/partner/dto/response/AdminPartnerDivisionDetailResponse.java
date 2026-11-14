package com.kovil.domain.partner.dto.response;

import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.enums.SameRegNoApplicationKind;
import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        파트너 부문 심사 상세(관리자). 신청자 · 사무소 정보 · 등록증을 한 화면에서 본다.
        **승인 기준은 대표자 이름 · 휴대폰 인증 · 서류다** — 사무소의 대표자 성명과 등록증을 맞춰 본다
        (휴대폰은 가입 때 인증을 마쳤다).
        """)
public record AdminPartnerDivisionDetailResponse(
        String divisionUuid,
        PartnerDivisionType division,
        PartnerDivisionStatus status,
        LocalDateTime submittedAt,
        @Schema(description = "마지막 반려 사유. 다시 낸 건이면 지난번 사유다") String rejectReason,
        @Schema(description = "마지막 정지 사유(내부 기록)") String suspendReason,
        LocalDateTime reviewedAt,
        @Schema(description = "마지막 승인 · 복구 시각") LocalDateTime lastApprovedAt,
        @Schema(description = "승인 · 반려 요청에 그대로 싣는다. 그사이 바뀌었으면 `PARTNER_DIVISION_CHANGED`(409)") Long version,
        AdminPartnerDivisionSummaryResponse.PartnerView partner,
        @Schema(description = "중개사무소. 대표자가 탈퇴해 연결이 끊겼으면 null") PartnerMeResponse.OfficeView office,
        @Schema(description = """
                등록증 단기 열람 URL. **캐시하거나 공유하지 않는다.** 만료되면 상세를 다시 받거나
                `GET /api/v1/files/{licenseFileUuid}/url` 로 다시 받는다. 제출 전이거나 폐기됐으면 null.
                """) FileReadUrlResponse license,
        @Schema(description = "그 사무소의 공개 중인 매물 수 — 정지하면 이만큼 노출에서 빠진다") long publicListingCount,
        @Schema(description = """
                같은 등록번호의 다른 신청. 같은 번호로 여러 명이 신청할 수 있고 **한 명만 승인**한다. `kind` 로 가른다.
                - `DIVISION_APPLICATION` — 그 번호로 낸 부문 신청, 또는 그 번호를 잡고 있는 사무소(`claimed=true`).
                  `claimed=true` 인 줄이 있으면 그 사무소가 이 번호를 승인받아 쓰고 있어 승인이 `OFFICE_REG_NO_DUPLICATED`(409)로 막힌다.
                - `OFFICE_CHANGE_REQUEST` — 승인된 다른 사무소가 이 번호로 **옮기려고** 낸 대기 중 변경 신청(`changeRequestUuid`).
                  그쪽이 먼저 승인되면 그 사무소가 번호를 잡아 이 신청의 승인이 409 가 된다.
                순서: 번호를 잡고 있는 줄 → 나머지 부문 신청 → 변경 신청(먼저 낸 것부터).
                대표자가 탈퇴해 떠난 사무소는 싣지 않는다(번호도 풀려 있고, 대기 중 변경 신청도 탈퇴가 닫는다).
                """) List<SameRegNoApplication> sameRegNoApplications,
        @Schema(description = """
                정지 이력(최근 정지 먼저) — 이 신청의 중개등록번호로 정지된 기록과 **이 사무소 자신**의 정지 기록(그때 번호가 지금과
                달라도)이 함께, 겹친 기록은 한 번만 실린다. 비어 있지 않으면 **"정지 이력이 있는 중개등록번호입니다"** 경고를 띄운다.
                **승인을 막지 않는다** — 관리자가 판단한다. 다른 사무소(떠난 대표자 포함)의 정지도, 이 사무소 자신의 복구된 정지도
                실린다(`officeUuid` 로 가른다 — 이 사무소면 자신의 정지, `regNo` 는 그때 번호).
                """) List<RegNoSuspensionView> regNoSuspensionHistory
) {

    @Schema(description = "같은 등록번호의 다른 신청 한 줄. 부문 심사 상세와 변경 심사 상세가 같은 모양으로 싣는다")
    public record SameRegNoApplication(
            @Schema(description = "줄의 종류 — 부문 신청(`DIVISION_APPLICATION`) · 대기 중 변경 신청(`OFFICE_CHANGE_REQUEST`)")
            SameRegNoApplicationKind kind,
            @Schema(description = "그 사람의 공인중개사 부문. 변경 신청 줄이면 신청자의 부문(없으면 null)") String divisionUuid,
            @Schema(description = "변경 신청 uuid — 변경 신청 줄에만 있다(부문 신청 줄은 null)") String changeRequestUuid,
            @Schema(description = "그 사무소") String officeUuid,
            @Schema(description = "그 사람의 공인중개사 부문 상태(변경 신청 줄도 신청자의 부문 상태다)") PartnerDivisionStatus status,
            @Schema(description = "제출 시각(변경 신청 줄이면 그 변경 신청을 낸 시각). 제출 전이면 null") LocalDateTime submittedAt,
            @Schema(description = "상호 — 그 번호와 함께 낸 값(변경 신청 줄이면 바꿀 상호)") String officeName,
            @Schema(description = "대표자 성명 — 그 번호와 함께 낸 값(변경 신청 줄이면 바꿀 대표자)") String ceoName,
            @Schema(description = "신청자 계정 상태") UserStatus accountStatus,
            @Schema(description = """
                    이 번호를 잡고 있는가 — 그 사무소가 마지막으로 승인받은 번호다. 승인 · 정지된 사무소가 잡고 있다
                    (번호를 바꾸는 변경 신청이 대기 · 반려 중이어도 그 사무소는 옛 번호로 영업 중이라 계속 잡는다).
                    변경 신청 줄은 늘 false 다 — 승인되기 전에는 그 번호를 잡지 않는다.
                    """) boolean claimed
    ) {
    }

    public static AdminPartnerDivisionDetailResponse of(PartnerDivision d,
                                                        AdminPartnerDivisionSummaryResponse.PartnerView partner,
                                                        PartnerMeResponse.OfficeView office,
                                                        FileReadUrlResponse license,
                                                        long publicListingCount,
                                                        List<SameRegNoApplication> sameRegNoApplications,
                                                        List<RegNoSuspensionView> regNoSuspensionHistory) {
        return new AdminPartnerDivisionDetailResponse(d.getUuid(), d.getDivision(), d.getStatus(),
                d.getSubmittedAt(), d.getRejectReason(), d.getSuspendReason(), d.getReviewedAt(),
                d.getLastApprovedAt(), d.getVersion(), partner, office, license, publicListingCount,
                sameRegNoApplications, regNoSuspensionHistory);
    }
}
