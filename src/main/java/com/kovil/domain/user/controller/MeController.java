package com.kovil.domain.user.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.user.dto.request.ConsentRequest;
import com.kovil.domain.user.dto.request.UpdateMeRequest;
import com.kovil.domain.user.dto.response.ConsentResponse;
import com.kovil.domain.user.dto.response.MeResponse;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Me", description = "내 정보 · 약관 동의 · 탈퇴 (인증 필요)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;
    private final UserQueryService userQueryService;

    @Operation(summary = "내 정보 조회", description = """
            **휴대폰 번호는 내려주지 않는다** — 서버에 암호문만 있고 화면은 `phoneVerified`만 알면 된다.

            `phoneVerified=false`면 임대 요청 단계로 진입할 수 없으므로 인증 유도 배너를 띄운다.
            """)
    @GetMapping
    public ApiResponse<MeResponse> getMe(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(userQueryService.getMe(principal.userUuid()));
    }

    @Operation(summary = "내 정보 수정", description = """
            **PATCH다 — 보내지 않은 필드는 바뀌지 않는다.** 언어만 바꾸려면 `preferredLanguage`만 보낸다.

            **화면 설계**
            - 언어를 바꾸면 응답의 `preferredLanguage`로 이후 요청의 `Accept-Language`를 갱신한다.
            - 닉네임은 v1 UI에 노출하지 않지만 API는 열려 있다(비익명 전환 대비).

            **에러 분기**
            - `NICKNAME_TAKEN`(409) — 다른 값을 제안한다.
            - `NICKNAME_BANNED`(400) / `NICKNAME_INVALID_FORMAT`(400) — 입력 필드에 표시.
            - `INVALID_REQUEST`(400) — 지원하지 않는 언어·국가 코드.
            """)
    @PatchMapping
    public ApiResponse<MeResponse> updateMe(
            @CurrentUser AuthenticatedUser principal, @Valid @RequestBody UpdateMeRequest request) {
        return ApiResponse.success(userService.updateMe(principal.userUuid(), request));
    }

    @Operation(summary = "약관 동의 현황", description = """
            **타입별 최신 1건**만 준다. 이력 전체가 아니라 현재 상태다.

            응답에 없는 타입은 **한 번도 동의·철회한 적이 없다**는 뜻이다.
            """)
    @GetMapping("/consents")
    public ApiResponse<List<ConsentResponse>> getConsents(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(userQueryService.getCurrentConsents(principal.userUuid()));
    }

    @Operation(summary = "약관 동의·철회", description = """
            **append-only다** — 철회도 `agreed=false`인 새 기록으로 남고 기존 기록은 수정되지 않는다
            (개인정보보호법 보존 의무).

            같은 타입을 여러 번 보내도 되며, 마지막 것이 현재 상태가 된다.
            """)
    @PostMapping("/consents")
    public ApiResponse<ConsentResponse> recordConsent(
            @CurrentUser AuthenticatedUser principal, @Valid @RequestBody ConsentRequest request) {
        return ApiResponse.success(userService.recordConsent(principal.userUuid(), request));
    }

    @Operation(summary = "탈퇴", description = """
            **되돌릴 수 없다.** 실행 전 확인 다이얼로그를 반드시 띄울 것.

            서버가 하는 일: 개인정보 삭제 · **모든 기기 로그아웃** · 푸시 수신 중단 ·
            휴대폰 번호 해제(**같은 번호로 재가입 가능**).
            커뮤니티 글은 익명으로 남는다.
            공인중개사 사무소 대표자(파트너)면 그 사무소가 맡은 진행 중 임대 요청이 대기로 돌아가고
            유학생마다 "다시 대기" 알림이 간다.

            클라이언트는 응답을 받으면 저장된 토큰을 지우고 첫 화면으로 보낸다.
            
            409 는 세 가지다. **코드로 갈라야 한다** — 하나는 재시도하면 되고 둘은 재시도해도 같다.
            - `CONFLICT` — 닉네임 재발급 경합. **탈퇴는 실행되지 않았다** — 그대로 재시도하면 된다.
            - `LAST_ADMIN_CANNOT_WITHDRAW` — 마지막 관리자다. 재시도해도 같은 답이며,
              다른 관리자를 지정한 뒤에야 탈퇴할 수 있다.
            - `PARTNER_SUSPENDED_CANNOT_WITHDRAW` — 파트너 부문이 정지돼 있다. 고객센터로 안내한다.
            """)
    @DeleteMapping
    public ApiResponse<Void> withdraw(@CurrentUser AuthenticatedUser principal) {
        userService.withdraw(principal.userUuid());
        return ApiResponse.ok();
    }
}
