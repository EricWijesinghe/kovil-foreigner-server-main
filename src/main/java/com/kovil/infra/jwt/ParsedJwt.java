package com.kovil.infra.jwt;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.enums.UserRole;

import java.time.Instant;
import java.util.Set;

public record ParsedJwt(
        String userUuid,
        UserRole role,
        Set<PartnerDivisionType> approvedDivisions,
        String jti,
        Instant issuedAt,
        Instant expiresAt
) {
}
