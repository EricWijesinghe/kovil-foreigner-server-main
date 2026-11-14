package com.kovil.domain.appversion.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = """
        플랫폼별 버전 정책 수정. 저장 즉시 다음 앱 실행부터 적용된다(배포 없음).

        - `APP_UPDATE_VERSION_INVALID`(400) — 버전이 1.2.3 형식의 숫자가 아니다.
        - `APP_UPDATE_MIN_ABOVE_LATEST`(400) — 최소지원버전 > 최신버전. 이 오설정을 저장하면
          최신 유저까지 스토어에 받을 것도 없이 강제 화면에 갇히므로 쓰기 시점에 막는다.
        - `APP_UPDATE_STORE_URL_INVALID`(400) — 스토어 URL이 https:// 로 시작하지 않는다.
          잘못된 URL은 강제 화면의 유일한 버튼을 죽은 버튼으로 만든다. 비우는 것은 허용 —
          비어 있는 동안은 어떤 판정도 발동하지 않는다.
        """)
public record AdminAppUpdatePolicyRequest(
        @Schema(description = "이 미만은 강제 업데이트(FORCE)", example = "1.0.0")
        String minSupportedVersion,
        @Schema(description = "이 미만은 권장 안내(RECOMMEND) 1회", example = "1.2.0")
        String latestVersion,
        @Schema(description = "스토어 이동 URL. 비우면 판정 발동 없음", example = "https://apps.apple.com/app/id0000000000")
        String storeUrl
) {}
