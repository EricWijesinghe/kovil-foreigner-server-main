package com.kovil.infra.jwt;

import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.exception.InvalidUserRoleException;
import com.kovil.infra.jwt.exception.JwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.kovil.infra.jwt.exception.JwtErrorCode.EXPIRED_TOKEN;
import static com.kovil.infra.jwt.exception.JwtErrorCode.INVALID_TOKEN;
import static com.kovil.infra.jwt.exception.JwtErrorCode.UNSUPPORTED_TOKEN;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String CLAIM_ROLE = "role";

    // 승인된 파트너 부문 이름 목록. 파트너가 아니면 싣지 않는다 — 없으면 빈 목록으로 읽는다
    // (이 클레임이 생기기 전에 발급된 유학생·관리자 토큰도 그대로 통해야 한다).
    private static final String CLAIM_DIVISIONS = "divisions";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret()));
    }

    public String issueAccessToken(String userUuid, UserRole role) {
        return issueAccessToken(userUuid, role, Set.of());
    }

    public String issueAccessToken(String userUuid, UserRole role,
                                   Collection<PartnerDivisionType> approvedDivisions) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(userUuid)
                .claim(CLAIM_ROLE, role.name());
        if (role == UserRole.PARTNER) {
            builder.claim(CLAIM_DIVISIONS, approvedDivisions == null ? List.of()
                    : approvedDivisions.stream().map(Enum::name).sorted().toList());
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public ParsedJwt parse(String accessToken) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(accessToken)
                    .getPayload();

            return new ParsedJwt(
                    claims.getSubject(),
                    roleOf(claims),
                    divisionsOf(claims),
                    claims.getId(),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new JwtException(EXPIRED_TOKEN);
        } catch (UnsupportedJwtException e) {
            throw new JwtException(UNSUPPORTED_TOKEN);
        } catch (IncorrectClaimException e) {
            log.warn("jwt claim mismatch: {}", e.getMessage());
            throw new JwtException(INVALID_TOKEN);
        } catch (MalformedJwtException | SignatureException | IllegalArgumentException e) {
            log.warn("invalid jwt: {}", e.getMessage());
            throw new JwtException(INVALID_TOKEN);
        }
    }

    /*
     * ★ 서명이 맞아도 **지금 없는 역할**이면 무효 토큰이다. 내부 담당자(MANAGER)와 옛 중개사(BROKER)
     *   역할이 사라지기 전에 발급된 토큰이 수명 동안 돌아다닌다 — 역할 해석 예외를 그대로 흘리면
     *   필터를 뚫고 500 이 난다. 무효로 돌려 401 을 받게 하면 앱은 리프레시로 새 역할의 토큰을 받는다.
     */
    private UserRole roleOf(Claims claims) {
        try {
            return UserRole.from(claims.get(CLAIM_ROLE, String.class));
        } catch (InvalidUserRoleException e) {
            log.warn("jwt with retired or unknown role: {}", claims.get(CLAIM_ROLE));
            throw new JwtException(INVALID_TOKEN);
        }
    }

    // 모르는 부문 이름도 무효다 — 일부만 버리고 통과시키면 토큰과 DB 의 부문이 어긋난 채 돌아다닌다.
    private Set<PartnerDivisionType> divisionsOf(Claims claims) {
        Object raw = claims.get(CLAIM_DIVISIONS);
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> names)) {
            throw new JwtException(INVALID_TOKEN);
        }
        Set<PartnerDivisionType> divisions = EnumSet.noneOf(PartnerDivisionType.class);
        for (Object name : names) {
            try {
                divisions.add(PartnerDivisionType.valueOf(String.valueOf(name)));
            } catch (IllegalArgumentException e) {
                log.warn("jwt with unknown partner division: {}", name);
                throw new JwtException(INVALID_TOKEN);
            }
        }
        return Set.copyOf(divisions);
    }
}
