package com.kovil.domain.partner.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = """
        정지 이력 한 건(관리자 전용). 화면 문구: **"정지 이력이 있는 중개등록번호입니다"**.
        **막지 않는다** — 승인 여부는 관리자가 판단한다. 복구된 정지도 남아 있다.
        심사 대상 번호로 정지된 기록과, 심사 중인 사무소 자신의 정지 기록(번호를 바꿨어도)이 함께 실린다 — `officeUuid` 로 가른다.
        """)
public record RegNoSuspensionView(
        @Schema(description = "정지 시각") LocalDateTime suspendedAt,
        @Schema(description = "정지 당시 그 사무소의 중개등록번호 — 이 사무소 자신의 정지면 지금 번호와 다를 수 있다") String regNo,
        @Schema(description = "정지된 사무소 uuid — 지금 심사 중인 사무소와 같으면 그 사무소 자신의 과거 정지다") String officeUuid,
        @Schema(description = "정지된 사무소의 상호(지금 값)") String officeName,
        @Schema(description = "정지 사유(관리자 내부 기록 — 파트너에게 보이지 않는다)") String reason
) {
}
