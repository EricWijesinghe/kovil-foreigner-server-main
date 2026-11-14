package com.kovil.domain.notification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.domain.notification.dto.response.AdminOutboxResponse;
import com.kovil.domain.notification.service.AdminNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "Admin · Notification", description = """
        발송 실패 대기열 (**ADMIN 전용**).

        알림은 정해진 횟수만큼 자동 재시도하고, 그래도 안 되면 **포기하고 여기 남긴다.**
        무한 재시도는 벤더 장애 때 큐를 태우고, 조용한 포기는 알림을 잃는다 —
        **포기했다는 사실이 남고 사람이 볼 수 있어야** 둘 다 피한다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasAuthority('PERM_NOTIFICATION_MANAGE')")
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;

    @Operation(summary = "발송 포기 대기열", description = """
            자동 재시도를 다 쓰고 실패한 알림들이다. 최신순.

            **여기가 비어 있는 것이 정상**이다. 쌓이기 시작하면 개별 유저 문제가 아니라
            벤더 쪽을 의심할 신호다 — `lastError`가 같은 값으로 몰려 있는지 먼저 본다.

            **유저는 인앱 알림함에서 이미 이 내용을 볼 수 있다** — 못 간 것은 푸시뿐이다.
            """)
    @GetMapping("/failed")
    public ApiResponse<PageResponse<AdminOutboxResponse>> findGivenUp(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminNotificationService.findGivenUp(Paging.of(page, size)));
    }

    @Operation(summary = "재시도", description = """
            큐에 다시 올린다. **시도 횟수가 0으로 돌아가** 기회를 새로 받는다.

            벤더 장애가 끝난 뒤 한 번에 되살리는 용도다.
            원인을 고치지 않고 누르면 같은 횟수만큼 실패하고 다시 이 목록으로 돌아온다.

            **GIVEN_UP 상태에서만 동작한다** — 이미 재시도돼 대기·발송된 행에 다시 누르면
            같은 푸시가 유저에게 중복 발송되므로 서버가 거절한다.

            - `NOTIFICATION_NOT_RETRYABLE`(409) — 이미 처리된 행. 화면은 "이미 처리됨"으로 안내한다.
            - `NOTIFICATION_NOT_FOUND`(404)
            """)
    @PostMapping("/{outboxUuid}/retry")
    public ApiResponse<Void> retry(@PathVariable String outboxUuid) {
        adminNotificationService.retry(outboxUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "테스트 푸시 발송 (본인)", description = """
            푸시 파이프라인 점검용 — **호출한 관리자 본인에게만** 보낸다.

            앱에 이 관리자 계정으로 로그인해 기기 토큰이 등록돼 있어야 단말까지 확인된다.
            토큰이 없으면 발송은 SKIPPED로 남는다(대기열에서 확인 가능).
            """)
    @PostMapping("/push-test")
    public ApiResponse<LocalDateTime> pushTest(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(adminNotificationService.sendTestPush(principal.userUuid()));
    }
}
