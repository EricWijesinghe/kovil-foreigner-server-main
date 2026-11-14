package com.kovil.domain.partner.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimited;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.auth.dto.response.SignInResponse;
import com.kovil.domain.partner.dto.request.BrokerageOfficeTelRequest;
import com.kovil.domain.partner.dto.request.PartnerSignUpRequest;
import com.kovil.domain.partner.dto.request.RealEstateBrokerSubmitRequest;
import com.kovil.domain.partner.dto.response.PartnerMeResponse;
import com.kovil.domain.partner.service.PartnerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Partner", description = """
        파트너스 앱(외부 파트너) — 가입 · 부문 제출 · 승인 뒤 변경 신청 · 대표번호 변경 · 내 상태.

        파트너는 가입하면서 **부문**을 신청하고(지금은 공인중개사 `REAL_ESTATE_BROKER` 하나),
        관리자가 부문별로 승인한다. **기능은 승인된 부문이 연다** — 승인 전 파트너의 `permissions` 는 비어 있다.
        공인중개사 부문은 사무소당 계정 하나 · 대표자만 가입한다.

        흐름: 휴대폰 인증 → `POST /signup`(토큰을 받는다) → 등록증 업로드(`purpose=BROKER_LICENSE`)
        → `PUT /divisions/REAL_ESTATE_BROKER`(제출) → 심사 → 승인.
        **승인 · 반려 · 복구는 세션을 끊지 않는다** — 앱이 리프레시(`POST /api/v1/auth/token/refresh`)로 새 토큰을 받으면
        새 상태의 기능이 열린다(로그인 상태 그대로). 정지만 세션을 끊는다(다시 로그인하면 기능이 닫힌 토큰을 받는다).
        심사 결과(승인 · 반려 · 정지 · 복구)는 푸시 + 알림함으로 온다(`PARTNER_DIVISION_*`, 딥링크 `/partner/me`) —
        받으면 `GET /me` 로 상태를 다시 읽고, 승인 · 복구면 리프레시한다. 반려 알림 본문에 사유가 실린다.

        승인 뒤 사무소 정보: **대표번호만** `PATCH /divisions/REAL_ESTATE_BROKER/office` 로 즉시 바꾼다.
        그 밖의 것(등록번호 · 상호 · 대표자 · 소재지 · 사업자번호 · 등록증)은 `PUT` 으로 **변경 신청**을 낸다 —
        심사 동안에도 부문 · 매물 · 기능이 **그대로**이고, 승인되면 그때 바뀐 값이 적용된다(반려되면 지금 정보 그대로).
        결과는 푸시 + 알림함(`OFFICE_CHANGE_APPROVED` · `OFFICE_CHANGE_REJECTED`, 딥링크 `/partner/me`)으로 온다.

        제출 · 변경 · 내 상태는 **파트너 계정만** 부른다(`NOT_A_PARTNER` 403). 권한이 아니라 역할로 거르는 이유:
        심사 중인 파트너는 권한이 하나도 없는데 바로 그 사람이 이것들을 써야 한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner")
public class PartnerController {

    private final PartnerService partnerService;

    @Operation(summary = "파트너 가입", description = """
            휴대폰 인증(`verificationToken`)이 선행되어야 한다. 성공 시 **201 + 토큰쌍**을 준다.
            계정(`PARTNER`) · 부문(`PENDING`, 제출 전) · 중개사무소가 함께 생긴다.
            같은 중개등록번호로 여러 명이 신청할 수 있다 — 관리자가 등록증을 보고 한 명만 승인한다.

            **에러 분기**
            - `OFFICE_REG_NO_DUPLICATED`(409) — 그 번호를 승인받은 사무소가 아직 잡고 있다(승인 · 정지된 사무소 — 번호를 바꾸는
              변경 신청이 대기 · 반려 중이어도 그 사무소는 옛 번호를 계속 쓴다). 대표자가 탈퇴하거나 변경 승인으로 번호를 옮기면 풀린다.
            - `DUPLICATE_EMAIL`(409) — 이메일만 바꿔 재시도하면 된다. **인증은 소비되지 않았다.**
            - `DUPLICATE_PHONE`(409) — 이미 가입된 번호다(유학생 계정 포함 — 번호는 계정당 하나).
            - `VERIFICATION_TOKEN_INVALID`(400) / `VERIFICATION_ALREADY_USED`(409) — 휴대폰 인증부터 다시.
            - `SUSPENDED_USER`(403) — 고객센터 안내.
            - `CONFLICT`(409) — 동시 요청 경합. 그대로 재시도하면 된다.
            - `INVALID_REQUEST`(400) + `validationErrors[]` — 필드별 코드로 폼에 표시.
            """)
    @RateLimited(RateLimitPolicy.SIGNUP)
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignInResponse>> signUp(@Valid @RequestBody PartnerSignUpRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(partnerService.signUp(request)));
    }

    @Operation(summary = "공인중개사 부문 제출 · 재제출 · 승인 뒤 변경 신청", description = """
            **첫 심사(제출 전 · `PENDING` · `REJECTED`)**: 사무소 정보 + 등록증(`licenseFileUuid`)을 내면 사무소를 덮고 심사 대기열에
            오른다(`submitted=true`). 반려된 건은 고쳐 내면 `PENDING` 으로 돌아간다. 심사 중에 다시 내도 되고, 그러면 대기열 뒤로 간다.
            등록증을 새 파일로 바꾸면 옛 파일은 폐기된다.

            **승인된 부문(`APPROVED`)이 내면 변경 신청이다** — 부문 · 사무소 · 매물 · 기능은 **그대로**이고(응답의 `brokerageOffice` 는
            지금 값), 바꿀 값은 `officeChange` 에 따로 실린다. 관리자가 승인하면 그때 사무소에 적용되고, 반려되면 지금 정보 그대로다.
            변경 심사 중에 다시 내면 그 신청을 덮어쓴다(대기열 뒤로 간다). **이때 `office.tel` 은 쓰지 않는다** — 대표번호는
            `PATCH …/office` 로 바로 바꾼다.

            - `PARTNER_REVIEW_NO_CHANGE`(400) — 승인된 부문인데 심사 대상(등록번호 · 상호 · 대표자 · 소재지 · 사업자번호 · 등록증)이
              지금 사무소 값과 하나도 다르지 않다.
            - `PARTNER_DIVISION_NOT_EDITABLE`(409) — 정지된 부문은 다시 낼 수도, 변경 신청을 낼 수도 없다.
            - `PARTNER_LICENSE_FILE_INVALID`(400) — 없는 파일 · 남의 파일 · 용도가 등록증이 아닌 파일.
            - `OFFICE_REG_NO_DUPLICATED`(409) — 그 번호를 다른 사무소가 승인받아 쓰고 있다.
            - `NOT_A_PARTNER`(403) — 파트너 계정이 아니다.
            - `CONFLICT`(409) — 같은 순간의 심사 · 대표번호 변경과 겹쳤다. 내 상태를 다시 읽고 재시도한다.
            """)
    @PutMapping("/divisions/REAL_ESTATE_BROKER")
    public ApiResponse<PartnerMeResponse> submitRealEstateBroker(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody RealEstateBrokerSubmitRequest request) {
        return ApiResponse.success(partnerService.submitRealEstateBroker(principal.userUuid(), request));
    }

    @Operation(summary = "중개사무소 대표번호 변경", description = """
            **대표번호만** 즉시 바꾼다 — 심사 없이, 부문 상태와 관계없이(심사 중 · 승인 · 반려 · 정지 모두).
            매물 화면의 공시 연락처가 바로 바뀐다. 심사 중인 신청의 판정에는 영향이 없다.

            - `PARTNER_DIVISION_NOT_FOUND`(404) — 공인중개사 부문(사무소)이 없다.
            - `NOT_A_PARTNER`(403) — 파트너 계정이 아니다.
            - `INVALID_REQUEST`(400) — 비었거나 30자를 넘는다.
            - `CONFLICT`(409) — 같은 순간의 심사 · 제출과 겹쳤다. 바뀌지 않았으니 그대로 재시도한다.
            """)
    @PatchMapping("/divisions/REAL_ESTATE_BROKER/office")
    public ApiResponse<PartnerMeResponse> changeOfficeTel(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody BrokerageOfficeTelRequest request) {
        return ApiResponse.success(partnerService.changeOfficeTel(principal.userUuid(), request.tel()));
    }

    @Operation(summary = "변경 신청 취소", description = """
            심사 중인 변경 신청(`officeChange.status=PENDING`)을 거둬들인다 → `CANCELED`.
            사무소 · 부문 · 매물은 처음부터 그대로라 되돌릴 것이 없다. 관리자 대기열에서 빠진다.
            응답의 `officeChange` 는 `status=CANCELED` 로 실린다 — 앱은 배너 없음(`null`)과 같게 그린다.
            신청에 붙인 새 등록증은 폐기된다(지금 사무소의 등록증을 그대로 냈으면 둔다). 정지된 부문에서도 취소할 수 있다.

            - `OFFICE_CHANGE_NOT_PENDING`(409) — 심사 중인 변경 신청이 없다(이미 승인 · 반려됐거나 낸 적이 없다).
              같은 순간 관리자가 처리했어도 이 답이다 — 내 상태를 다시 읽는다.
            - `PARTNER_DIVISION_NOT_FOUND`(404) — 공인중개사 부문(사무소)이 없다.
            - `NOT_A_PARTNER`(403) — 파트너 계정이 아니다.
            - `CONFLICT`(409) — 같은 순간의 다른 처리와 겹쳤다. 내 상태를 다시 읽고 재시도한다.
            """)
    @DeleteMapping("/divisions/REAL_ESTATE_BROKER/office-change")
    public ApiResponse<PartnerMeResponse> cancelOfficeChange(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(partnerService.cancelOfficeChange(principal.userUuid()));
    }

    @Operation(summary = "내 부문 상태 · 사무소 정보", description = """
            앱이 이걸로 "제출 전 · 심사 중 · 반려(사유) · 승인 · 정지"와, 승인 뒤의 "변경 심사 중 · 변경 반려(사유)"(`officeChange`)를 그린다.
            `/api/v1/me` 의 `permissions` 는 서버의 지금 상태다. 앱이 들고 있는 토큰은 **발급 시점의** 부문을 싣는다 —
            여기서 `APPROVED`(승인 · 복구)를 봤으면 리프레시로 새 토큰을 받아야 그 기능의 API 가 열린다.
            """)
    @GetMapping("/me")
    public ApiResponse<PartnerMeResponse> me(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(partnerService.getMe(principal.userUuid()));
    }
}
