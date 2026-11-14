package com.kovil.common.security.filter;

import com.kovil.common.security.AuthenticatedUser;
import com.kovil.infra.jwt.BlacklistedTokenStore;
import com.kovil.infra.jwt.JwtTokenProvider;
import com.kovil.infra.jwt.ParsedJwt;
import com.kovil.infra.jwt.exception.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final BlacklistedTokenStore blacklistedTokenStore;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            ParsedJwt parsed = jwtTokenProvider.parse(token);

            BlacklistedTokenStore.Rejection rejection = blacklistedTokenStore.check(
                    parsed.jti(), parsed.userUuid(), parsed.issuedAt());
            if (rejection != BlacklistedTokenStore.Rejection.NONE) {
                log.warn("jwt rejected: {}", rejection);
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            AuthenticatedUser principal = new AuthenticatedUser(
                    parsed.userUuid(), parsed.role(), parsed.jti(), parsed.expiresAt(),
                    parsed.approvedDivisions());

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + parsed.role().name()));
            parsed.role().permissionsWith(parsed.approvedDivisions())
                    .forEach(permission -> authorities.add(
                            new SimpleGrantedAuthority("PERM_" + permission.name())));

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));

        } catch (JwtException e) {
            log.warn("jwt rejected: {}", e.getErrorCode().getCode());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }
}
