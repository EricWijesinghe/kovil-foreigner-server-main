package com.kovil.domain.auth.dto;

import com.kovil.domain.partner.enums.PartnerDivisionType;

import java.util.Set;

// approvedDivisions 는 액세스 토큰에 실린 부문이다 — 로그인 응답의 permissions 가 토큰과 같은 값을 보여야 한다.
public record TokenPair(String accessToken, String refreshToken, long accessTokenExpiresIn,
                        Set<PartnerDivisionType> approvedDivisions) {

    public TokenPair {
        approvedDivisions = approvedDivisions == null ? Set.of() : Set.copyOf(approvedDivisions);
    }

    public TokenPair(String accessToken, String refreshToken, long accessTokenExpiresIn) {
        this(accessToken, refreshToken, accessTokenExpiresIn, Set.of());
    }
}
