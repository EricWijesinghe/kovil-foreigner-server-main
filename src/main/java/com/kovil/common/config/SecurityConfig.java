package com.kovil.common.config;

import com.kovil.common.dto.response.ApiResponse;
import com.kovil.common.exception.ErrorCode;
import com.kovil.common.exception.ErrorMessageResolver;
import com.kovil.common.security.exception.SecurityErrorCode;
import com.kovil.common.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/error",
            "/actuator/health",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private static final String[] PUBLIC_POST_PATHS = {
            "/api/v1/auth/phone/send",
            "/api/v1/auth/phone/verify",
            "/api/v1/auth/signup",
            "/api/v1/partner/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/password/reset",
            "/api/v1/auth/email/find",
            "/api/v1/auth/token/refresh",
            "/api/v1/auth/oauth/*",
            "/api/v1/auth/oauth/*/link",
            // 한 칸 패턴(*)으로는 두 칸 경로가 안 열린다. 정확한 경로만 연다.
            "/api/v1/auth/oauth/apple/native",
            // Apple 이 직접 POST 한다. 되돌림(form_post)과 서버 간 알림 둘 다 Apple 만 쓰므로 apple 경로만 연다.
            "/oauth/apple/callback",
            "/oauth/apple/notifications"
    };

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/app-version/check",
            "/api/v1/languages",
            "/api/v1/countries",
            "/api/v1/regions",
            "/api/v1/auth/oauth/*/authorize",
            "/api/v1/auth/oauth/apple/nonce",
            "/oauth/*/callback",
            "/api/v1/listings",
            "/api/v1/listings/*",
            "/api/v1/listings/*/nearby",
            "/api/v1/universities",
            "/api/v1/transit/subway-stations",
            "/api/v1/search/places",
            "/api/v1/partner/address/search"
    };

    private static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization", "Content-Type", "Accept", "Accept-Language"
    );

    private static final List<String> ALLOWED_METHODS = List.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"
    );

    private final JsonMapper jsonMapper;
    private final ErrorMessageResolver errorMessageResolver;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.warn("Unauthorized: {} {}", request.getMethod(), request.getRequestURI());
                            sendErrorResponse(request, response, SecurityErrorCode.UNAUTHORIZED_ACCESS);
                        })
                        .accessDeniedHandler((request, response, deniedException) -> {
                            log.warn("Forbidden: {} {}", request.getMethod(), request.getRequestURI());
                            sendErrorResponse(request, response, SecurityErrorCode.FORBIDDEN_ACCESS);
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        // 좌표(네이버 과금)는 권한을 필터에서 먼저 본다 — 요청 제한 인터셉터가 @PreAuthorize 보다 앞이라,
                        // 여기서 안 막으면 권한 없는 계정의 403 호출이 중개사들의 전체 하루 상한을 깎아 먹는다.
                        .requestMatchers(HttpMethod.GET, "/api/v1/partner/address/coordinate")
                        .hasAuthority("PERM_LISTING_MANAGE")
                        .anyRequest().authenticated());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!corsProperties.isEnabled()) {
            return source;
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(corsProperties.allowedOriginPatterns());
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setAllowCredentials(false);
        // 429 의 남은 시간(초). CORS 안전 목록 밖 헤더라 노출하지 않으면 웹 프런트 스크립트가 못 읽음.
        config.setExposedHeaders(List.of("Retry-After"));
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("USER")
                .role("PARTNER").implies("USER")
                .build();
    }

    private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response,
                                   ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(
                ApiResponse.failure(errorCode, errorMessageResolver.resolve(errorCode, request.getLocale()))));
    }
}
