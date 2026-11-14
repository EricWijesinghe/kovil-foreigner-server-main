package com.kovil.domain.refreshtoken.dto.result;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.enums.UserRole;

import java.util.Set;

public record RefreshTokenIssueResult(
        String rawToken,
        Long refreshTokenId,
        String userUuid,
        UserRole role,
        // 액세스 토큰의 divisions 클레임. 파트너가 아니면 비어 있다.
        Set<PartnerDivisionType> approvedDivisions
) {
    public RefreshTokenIssueResult {
        approvedDivisions = approvedDivisions == null ? Set.of() : Set.copyOf(approvedDivisions);
    }

    public RefreshTokenIssueResult(String rawToken, Long refreshTokenId, String userUuid, UserRole role) {
        this(rawToken, refreshTokenId, userUuid, role, Set.of());
    }
}
