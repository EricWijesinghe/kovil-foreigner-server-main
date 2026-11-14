package com.kovil.common.security;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.enums.UserRole;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record AuthenticatedUser(
        String userUuid,
        UserRole role,
        String jti,
        Instant expiresAt,
        Set<PartnerDivisionType> approvedDivisions
) {
    public AuthenticatedUser {
        approvedDivisions = approvedDivisions == null ? Set.of() : Set.copyOf(approvedDivisions);
    }

    public AuthenticatedUser(String userUuid, UserRole role, String jti, Instant expiresAt) {
        this(userUuid, role, jti, expiresAt, Set.of());
    }

    public Duration remaining() {
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
