package com.kovil.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = """
        발송을 포기한 알림. **자동 재시도를 멈춘 건들이다** — 사람이 보고 판단해야 한다.

        여기 쌓인다는 것은 정해진 횟수만큼 시도했는데도 전부 실패했다는 뜻이다.
        원인은 대개 셋 중 하나다: 벤더 장애가 길어짐 · 크리덴셜 만료 · 그 유저의 기기 문제.
        `lastError`가 첫 단서다.
        """)
public record AdminOutboxResponse(

        @Schema(description = "outbox uuid. 재시도에 쓴다.") String outboxUuid,

        @Schema(description = """
                발송 채널. **v1은 `PUSH` 하나다** — 알림은 FCM 푸시와 인앱 알림함으로만 나가고,
                SMS는 휴대폰 인증 전용이라 이 큐를 지나지 않는다. 화면에서 생략해도 된다.
                """, example = "PUSH") String channel,

        @Schema(description = "수신자 uuid") String userUuid,

        @Schema(description = "수신자 이름. 탈퇴로 지워졌으면 null", example = "BATSAIKHAN ENKH")
        String recipientName,

        @Schema(description = "알림 타입", example = "RENTAL_REQUEST_IN_PROGRESS") String notiType,

        @Schema(description = "멱등 키 — **어떤 사건의 알림인지 여기서 읽힌다**",
                example = "RENTAL_REQUEST_IN_PROGRESS:request:c4cf2d9e:2026-10-07T10:00") String dedupeKey,

        @Schema(description = "시도 횟수", example = "5") Integer attemptCount,

        @Schema(description = """
                마지막 실패 사유. **원인 추적의 첫 단서다.** 벤더가 준 문자열을 **가공하지 않고**
                그대로 싣는다 — 이 화면의 목적이 원문을 읽고 판단하는 것이다.
                코드와 메시지를 나누지 않는 이유도 같다(나누는 순간 서버가 해석을 강요한다).
                """) String lastError,

        @Schema(description = "적재 시각") LocalDateTime createdAt,

        @Schema(description = "**마지막 시도 시각**. 상태가 바뀌는 유일한 계기가 시도라서 이 값이 곧 그것이다.")
        LocalDateTime updatedAt
) {
}
