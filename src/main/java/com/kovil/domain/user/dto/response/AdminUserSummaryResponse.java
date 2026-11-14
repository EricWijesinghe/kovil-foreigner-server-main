package com.kovil.domain.user.dto.response;

import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "회원 목록 행 (ADMIN 전용).")
public record AdminUserSummaryResponse(
        String userUuid,
        String email,
        String name,
        String nickname,
        UserRole role,
        UserStatus status,
        String preferredLanguage,
        LocalDateTime createdAt
) {
}
