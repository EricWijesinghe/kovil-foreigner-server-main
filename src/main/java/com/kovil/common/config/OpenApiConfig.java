package com.kovil.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 이 설정이 만드는 open_api.json이 FE 계약의 정본이다.
// 전역 SecurityRequirement를 두면 공개 경로에도 잠금 아이콘이 붙지만,
// 서버가 그 경로에서 토큰을 무시하므로 기능상 문제는 없다 — 대부분이 인증을 요구하므로 전역이 자연스럽다.
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI kovilOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, bearerJwtScheme()))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    private Info apiInfo() {
        return new Info()
                .title("KOVIL API")
                .version("v1")
                .description("""
                        한국 거주 외국인 대상 임대 플랫폼 백엔드 API.

                        ## 공통 응답 봉투
                        모든 응답은 `ApiResponse<T>`로 감싸진다.
                        - 성공: `success=true`, `data`에 payload. 실패 필드는 아예 나가지 않는다.
                        - 실패: `success=false`, `errorCode`(분기 축), `message`(사용자 노출용).
                          HTTP status는 errorCode가 결정한다.
                        - 입력 검증 실패: `errorCode=INVALID_REQUEST` + `validationErrors[]`에 필드별 코드.
                          **`validationErrors`의 항목은 필드명이 아니라 `errorCode`다** — FE는 코드로 분기한다.

                        ## 언어
                        `Accept-Language` 헤더가 응답 문구와 마스터 데이터 표기를 결정한다.
                        지원: `ko`, `en`. 그 외 언어는 영어로 폴백한다.
                        **로그인 후에는 응답의 `preferredLanguage`로 이 헤더를 채워 보내면 된다** —
                        서버는 저장된 언어를 매 요청 조회하지 않는다.

                        ## 인증
                        `Authorization: Bearer <accessToken>`.
                        액세스 토큰은 짧고(기본 30분) 리프레시 토큰으로 회전한다.
                        `accessTokenExpiresIn`(초)을 보고 만료 전에 `/auth/token/refresh`를 부르면 된다.
                        **리프레시는 1회용이다** — 회전하면 옛 토큰은 즉시 폐기되고,
                        폐기된 토큰을 다시 쓰면 그 계정의 모든 세션이 끊긴다(탈취 방어).

                        ## 속도 제한
                        초과 시 `429` + `errorCode=RATE_LIMITED` + **`Retry-After` 헤더(초)**.
                        FE는 이 헤더로 재시도 시점을 안내하면 된다.
                        """);
    }

    private SecurityScheme bearerJwtScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("로그인·토큰 재발급으로 받은 액세스 토큰(JWT). 값만 붙여넣으면 됩니다.");
    }
}
