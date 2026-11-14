package com.kovil.domain.partner.dto.response;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = """
        파트너 본인의 부문 상태와 부문 상세. 파트너스 앱이 이걸로 "제출 전 · 심사 중 · 반려 · 승인 · 정지"를 그린다.

        - `submitted=false` 면 가입만 하고 서류를 내지 않은 상태다 — 제출 화면으로 보낸다.
        - `PENDING` + `submitted=true` 면 심사 중이다.
        - `REJECTED` 면 `rejectReason` 을 보여 주고 고쳐 다시 내게 한다.
        - `APPROVED` 면 그 부문의 기능이 열린다(로그인 응답 · `/me` 의 `permissions`). 승인 직후 앱이 들고 있는
          토큰은 아직 옛 권한이다 — 리프레시(`POST /api/v1/auth/token/refresh`)로 새 토큰을 받으면 열린다.
        - `SUSPENDED` 면 기능이 닫히고 그 사무소 매물이 공개에서 빠진다. 사유는 싣지 않는다 — 고객센터 안내.

        승인 뒤 사무소 정보 변경은 `officeChange`(최근 변경 신청 1건)로 그린다 — `PENDING` 이면 "변경 심사 중"(그동안에도
        매물 · 기능은 그대로이고 `brokerageOffice` 는 지금 값이다), `REJECTED` 면 사유와 함께 "변경 반려"(지금 정보 그대로).
        `CANCELED` 는 신청자가 거둬들인 것이다 — 배너 없음(`null`)과 같게 그린다.
        """)
public record PartnerMeResponse(
        String userUuid,
        @Schema(description = "가입자(대표자) 이름") String name,
        String email,
        @Schema(description = "본인(대표자) 휴대폰 — 진행 중 임대 요청에서 유학생에게 보이는 대표자 휴대폰(`ceoPhone`)과 같은 값 · 같은 표기(숫자만). "
                + "본인만 보는 응답이라 가리지 않는다. 바꾸는 API 는 없다. 복호화에 실패하면 null", example = "01000000000")
        String phone,
        @Schema(description = "신청한 부문들(지금은 공인중개사 하나)") List<DivisionView> divisions,
        @Schema(description = "공인중개사 부문의 상세 — 중개사무소의 **지금** 값. 없으면 null") OfficeView brokerageOffice,
        @Schema(description = "그 사무소의 최근 변경 신청 1건(승인 뒤 정보 변경). 낸 적이 없으면 null") OfficeChangeView officeChange
) {

    public record DivisionView(
            String divisionUuid,
            PartnerDivisionType division,
            PartnerDivisionStatus status,
            @Schema(description = "서류까지 냈는가. false 면 심사 대기열에 아직 없다") boolean submitted,
            LocalDateTime submittedAt,
            @Schema(description = "반려 사유. `REJECTED` 일 때만 실린다") String rejectReason,
            @Schema(description = "마지막 심사 시각") LocalDateTime reviewedAt,
            @Schema(description = "마지막 승인 · 복구 시각") LocalDateTime lastApprovedAt
    ) {
        public static DivisionView of(PartnerDivision d) {
            return new DivisionView(d.getUuid(), d.getDivision(), d.getStatus(), d.isSubmitted(),
                    d.getSubmittedAt(),
                    // 다시 내면 대기로 돌아가는데 옛 사유가 남아 있으면 "아직 반려 상태"로 읽힌다.
                    d.getStatus() == PartnerDivisionStatus.REJECTED ? d.getRejectReason() : null,
                    d.getReviewedAt(), d.getLastApprovedAt());
        }
    }

    @Schema(name = "PartnerOfficeView", description = "파트너 본인 · 관리자 심사 화면의 중개사무소 — 대표번호 · 사업자번호 · 등록증까지")
    public record OfficeView(
            String officeUuid,
            String regNo,
            String name,
            String ceoName,
            String address,
            String tel,
            String bizNo,
            @Schema(description = "제출한 등록증 파일 uuid. 열람은 `GET /api/v1/files/{fileUuid}/url`") String licenseFileUuid
    ) {
        public static OfficeView of(BrokerageOffice o, String licenseFileUuid) {
            return new OfficeView(o.getUuid(), o.getRegNo(), o.getName(), o.getCeoName(), o.getAddress(),
                    o.getTel(), o.getBizNo(), licenseFileUuid);
        }
    }

    public record OfficeChangeView(
            String requestUuid,
            @Schema(description = "PENDING(변경 심사 중) · APPROVED(적용됨) · REJECTED(반려 — 지금 정보 그대로) · "
                    + "CANCELED(신청자가 거둬들임 — 지금 정보 그대로, 앱은 배너 없음으로 그린다)")
            OfficeChangeRequestStatus status,
            @Schema(description = "마지막으로 낸 시각") LocalDateTime submittedAt,
            @Schema(description = "반려 사유. `REJECTED` 일 때만 실린다") String rejectReason,
            @Schema(description = "승인 · 반려 시각") LocalDateTime reviewedAt,
            @Schema(description = "바꿀 값 한 벌") OfficeChangeValues requested
    ) {
        public static OfficeChangeView of(BrokerageOfficeChangeRequest request, String licenseFileUuid) {
            return new OfficeChangeView(request.getUuid(), request.getStatus(), request.getSubmittedAt(),
                    request.getStatus() == OfficeChangeRequestStatus.REJECTED ? request.getRejectReason() : null,
                    request.getReviewedAt(), OfficeChangeValues.of(request, licenseFileUuid));
        }
    }

    public static PartnerMeResponse of(User user, String phone, List<PartnerDivision> divisions, OfficeView office,
                                       OfficeChangeView officeChange) {
        return new PartnerMeResponse(user.getUuid(), user.getName(), user.getEmail(), phone,
                divisions.stream().map(DivisionView::of).toList(), office, officeChange);
    }
}