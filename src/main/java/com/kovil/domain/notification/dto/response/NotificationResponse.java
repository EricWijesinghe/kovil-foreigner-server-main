package com.kovil.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = """
        인앱 알림 1건. **푸시를 놓치거나 꺼 둔 유저의 복구 경로**다 —
        푸시가 유일한 전달 수단이면 안 된다.

        ★ **문구는 조회 시점의 언어로 렌더된다.** 서버는 키와 치환 값만 저장하므로,
        유저가 언어를 바꾸면 **과거 알림도 그 언어로 읽힌다**(FR-603).
        """)
public record NotificationResponse(

        @Schema(description = "알림 uuid. 읽음 처리에 쓴다.") String notificationUuid,

        @Schema(description = "알림 타입. 화면 아이콘 분기에 쓴다.", example = "RENTAL_REQUEST_IN_PROGRESS") String notiType,

        @Schema(description = "CONTRACT · COMMUNITY · MARKETING", example = "CONTRACT") String category,

        @Schema(description = "제목(현재 언어)", example = "행복공인중개사사무소에서 요청을 맡았습니다") String title,

        @Schema(description = "본문(현재 언어)") String body,

        @Schema(description = "탭했을 때 이동할 앱 내 경로. null이면 이동 없이 알림함에만 남는다.",
                example = "/rental-requests/c4cf2d9e") String deepLink,

        @Schema(description = "읽은 시각. null이면 미읽음 — 뱃지 카운트의 축이다.") LocalDateTime readAt,

        @Schema(description = "수신 시각") LocalDateTime createdAt
) {
}
