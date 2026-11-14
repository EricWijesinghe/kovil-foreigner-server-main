package com.kovil.domain.appversion.dto.response;

import com.kovil.domain.appversion.enums.UpdateVerdict;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ★ 이 계약은 스토어에 나간 모든 바이너리에 박제된다 — 필드 삭제·의미 변경 금지, 추가만 가능.
 */
@Schema(description = """
        앱 버전 판정. 앱은 버전 비교를 직접 하지 않고 이 판정대로만 움직인다.

        - `FORCE`: 닫을 수 없는 전체 화면 + [업데이트] 버튼(→ `storeUrl`).
        - `RECOMMEND`: 안내 모달 1회 — [나중에]로 계속 쓸 수 있고, 같은 `latestVersion`에 대해 다시 묻지 않는다.
        - `OK`: 아무것도 그리지 않는다.

        FORCE/RECOMMEND는 `storeUrl`이 있을 때만 온다 — 보낼 곳 없는 강제 화면은 락아웃이라 서버가 발동 자체를 막는다.
        판정 API가 실패하면 앱은 통과시킨다(fail-open) — 서버 장애가 전 유저 차단이 되면 안 된다.
        """)
public record AppVersionCheckResponse(
        UpdateVerdict verdict,
        @Schema(description = "정책상 최신 버전 — RECOMMEND '이 버전에 대해 한 번만 안내' 키로도 쓴다", example = "1.2.0")
        String latestVersion,
        @Schema(description = "스토어 이동 URL. OK이거나 미설정이면 null", example = "https://apps.apple.com/app/id0000000000")
        String storeUrl
) {}
