package com.kovil.domain.notification.controller;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.web.Paging;
import com.kovil.common.dto.response.PageResponse;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.common.security.annotation.CurrentUser;
import com.kovil.domain.notification.dto.request.NotificationSettingRequest;
import com.kovil.domain.notification.dto.response.NotificationResponse;
import com.kovil.domain.notification.dto.response.NotificationSettingResponse;
import com.kovil.domain.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Notification", description = """
        인앱 알림함과 수신 설정.

        **푸시가 유일한 전달 수단이 아니다** — 푸시를 놓치거나 꺼 둔 유저도 여기서 다 볼 수 있다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @Operation(summary = "알림함", description = """
            최신순.

            ★ **문구는 지금 언어로 렌더된다.** 서버가 저장하는 것은 키와 치환 값뿐이라,
            유저가 언어를 바꾸면 **과거 알림도 그 언어로 읽힌다**(`Accept-Language`가 축이다).

            `deepLink`가 있으면 탭 시 이동시키고, null이면 알림함에만 남긴다.
            """)
    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> findMine(
            @CurrentUser AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(notificationQueryService.findMine(principal.userUuid(), Paging.of(page, size)));
    }

    @Operation(summary = "미읽음 개수", description = "탭 뱃지에 쓴다.")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(
                Map.of("count", notificationQueryService.countUnread(principal.userUuid())));
    }

    @Operation(summary = "읽음 처리", description = """
            이미 읽은 알림을 다시 호출해도 **처음 읽은 시각은 덮어쓰지 않는다.**
            """)
    @PostMapping("/{notificationUuid}/read")
    public ApiResponse<Void> markRead(
            @CurrentUser AuthenticatedUser principal,
            @PathVariable String notificationUuid) {
        notificationQueryService.markRead(principal.userUuid(), notificationUuid);
        return ApiResponse.ok();
    }

    @Operation(summary = "모두 읽음")
    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead(@CurrentUser AuthenticatedUser principal) {
        notificationQueryService.markAllRead(principal.userUuid());
        return ApiResponse.ok();
    }

    @Operation(summary = "수신 설정 조회", description = """
            ★ **`CONTRACT`는 목록에 없다**(FR-608) — 월세·계약·인증은 끌 수 없다.
            꺼 두면 "몰라서 못 냈다"가 성립해 버린다.

            설정한 적이 없으면 `true`로 내려간다.
            """)
    @GetMapping("/settings")
    public ApiResponse<List<NotificationSettingResponse>> findSettings(
            @CurrentUser AuthenticatedUser principal) {
        return ApiResponse.success(notificationQueryService.findSettings(principal.userUuid()));
    }

    @Operation(summary = "수신 설정 변경", description = """
            `COMMUNITY` · `MARKETING`만 지정할 수 있다.
            `CONTRACT`를 보내면 `NOTIFICATION_CATEGORY_NOT_TOGGLEABLE`(400)이다.

            끄면 **푸시만 멈춘다** — 인앱 알림함에는 그대로 쌓인다.
            """)
    @PutMapping("/settings")
    public ApiResponse<List<NotificationSettingResponse>> changeSetting(
            @CurrentUser AuthenticatedUser principal,
            @Valid @RequestBody NotificationSettingRequest request) {
        return ApiResponse.success(notificationQueryService.changeSetting(
                principal.userUuid(), request.category(), Boolean.TRUE.equals(request.enabled())));
    }
}
