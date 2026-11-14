package com.kovil.domain.auth.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    PHONE_NOT_KR(
            "PHONE_NOT_KR",
            "한국 휴대폰 번호만 사용할 수 있습니다.",
            HttpStatus.BAD_REQUEST
    ),
    PHONE_REQUIRED(
            "PHONE_REQUIRED",
            "휴대폰 번호는 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_CODE_REQUIRED(
            "VERIFICATION_CODE_REQUIRED",
            "인증번호는 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    // 만료·미발급·시도초과로 폐기된 경우를 하나로 묶는다. 구분해서 알려주면
    // 어떤 번호로 발송이 있었는지를 외부에서 알아낼 수 있다.
    VERIFICATION_CODE_INVALID(
            "VERIFICATION_CODE_INVALID",
            "인증번호가 올바르지 않거나 만료되었습니다. 다시 요청해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_RESEND_TOO_SOON(
            "VERIFICATION_RESEND_TOO_SOON",
            "잠시 후 다시 요청해 주세요.",
            HttpStatus.TOO_MANY_REQUESTS
    ),
    VERIFICATION_TOKEN_INVALID(
            "VERIFICATION_TOKEN_INVALID",
            "인증 정보가 만료되었습니다. 휴대폰 인증을 다시 진행해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    INVALID_VERIFICATION_PURPOSE(
            "INVALID_VERIFICATION_PURPOSE",
            "올바르지 않은 인증 목적입니다.",
            HttpStatus.BAD_REQUEST
    ),
    // 이 인증으로 이미 가입이 끝났다(FR-105). 한 인증은 한 번만 소비된다.
    VERIFICATION_ALREADY_USED(
            "VERIFICATION_ALREADY_USED",
            "이미 사용된 인증입니다. 휴대폰 인증을 다시 진행해 주세요.",
            HttpStatus.CONFLICT
    ),

    RESET_ACCOUNT_NOT_FOUND(
            "RESET_ACCOUNT_NOT_FOUND",
            "이 번호로 가입된 계정이 없습니다.",
            HttpStatus.NOT_FOUND
    ),

    RESET_NOT_LOCAL_ACCOUNT(
            "RESET_NOT_LOCAL_ACCOUNT",
            "소셜 로그인으로 가입된 번호입니다. Apple·Google·Facebook으로 로그인해 주세요.",
            HttpStatus.CONFLICT
    ),
    DUPLICATE_PHONE(
            "DUPLICATE_PHONE",
            "이미 가입된 휴대폰 번호입니다.",
            HttpStatus.CONFLICT
    ),
    DUPLICATE_EMAIL(
            "DUPLICATE_EMAIL",
            "이미 사용 중인 이메일입니다.",
            HttpStatus.CONFLICT
    ),
    SUSPENDED_USER(
            "SUSPENDED_USER",
            "정지된 계정입니다. 고객센터로 문의해 주세요.",
            HttpStatus.FORBIDDEN
    ),
    // 계정 없음과 비밀번호 불일치를 구분하지 않는다 — 가입 여부가 새어 나간다.
    INVALID_CREDENTIALS(
            "INVALID_CREDENTIALS",
            "이메일 또는 비밀번호가 올바르지 않습니다.",
            HttpStatus.UNAUTHORIZED
    ),
    USER_NOT_ACTIVE(
            "USER_NOT_ACTIVE",
            "사용할 수 없는 계정입니다.",
            HttpStatus.FORBIDDEN
    ),
    EMAIL_REQUIRED(
            "EMAIL_REQUIRED",
            "이메일은 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    EMAIL_INVALID_FORMAT(
            "EMAIL_INVALID_FORMAT",
            "올바른 이메일 형식이 아닙니다.",
            HttpStatus.BAD_REQUEST
    ),
    PASSWORD_REQUIRED(
            "PASSWORD_REQUIRED",
            "비밀번호는 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    PASSWORD_INVALID_FORMAT(
            "PASSWORD_INVALID_FORMAT",
            "비밀번호는 8~64자이며 영문과 숫자를 포함해야 합니다.",
            HttpStatus.BAD_REQUEST
    ),
    UNSUPPORTED_OAUTH_PROVIDER(
            "UNSUPPORTED_OAUTH_PROVIDER",
            "지원하지 않는 로그인 수단입니다.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_PROVIDER_DISABLED(
            "OAUTH_PROVIDER_DISABLED",
            "지금은 사용할 수 없는 로그인 수단입니다.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_STATE_INVALID(
            "OAUTH_STATE_INVALID",
            "로그인 요청이 만료되었습니다. 다시 시도해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_TOKEN_INVALID(
            "OAUTH_TOKEN_INVALID",
            "소셜 로그인 정보를 확인하지 못했습니다. 다시 시도해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_TEMP_TOKEN_EXPIRED(
            "OAUTH_TEMP_TOKEN_EXPIRED",
            "로그인 정보가 만료되었습니다. 다시 시도해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_EMAIL_REQUIRED(
            "OAUTH_EMAIL_REQUIRED",
            "소셜 계정에서 이메일을 받지 못했습니다. 이메일 제공에 동의하거나 다른 방법으로 가입해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_PKCE_INVALID(
            "OAUTH_PKCE_INVALID",
            "로그인 요청을 확인하지 못했습니다. 다시 시도해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    OAUTH_ALREADY_LINKED(
            "OAUTH_ALREADY_LINKED",
            "이 소셜 계정은 이미 다른 계정에 연결되어 있습니다.",
            HttpStatus.CONFLICT
    ),
    // provider 장애 · 시한 초과. 사용자 탓이 아니라 "잠시 뒤 다시"라 503 이다(주소 검색 장애와 같은 축).
    OAUTH_PROVIDER_UNAVAILABLE(
            "OAUTH_PROVIDER_UNAVAILABLE",
            "지금은 소셜 로그인을 할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.SERVICE_UNAVAILABLE
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
