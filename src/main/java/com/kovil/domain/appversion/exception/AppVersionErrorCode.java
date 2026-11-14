package com.kovil.domain.appversion.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 관리자 정책 편집의 쓰기 시점 검증. 판정(check) 쪽은 에러를 내지 않는다 —
 * 이미 나간 바이너리가 부르는 공개 엔드포인트는 무엇을 받든 OK 봉투로 답한다(fail-open).
 * 잘못된 값은 **사람이 보고 있는 지금** 막는 것이 유일하게 안전한 지점이다.
 */
@Getter
@RequiredArgsConstructor
public enum AppVersionErrorCode implements ErrorCode {

    APP_UPDATE_PLATFORM_INVALID(
            "APP_UPDATE_PLATFORM_INVALID",
            "지원하지 않는 플랫폼입니다.",
            HttpStatus.NOT_FOUND
    ),
    APP_UPDATE_VERSION_INVALID(
            "APP_UPDATE_VERSION_INVALID",
            "버전은 1.2.3 형식의 숫자여야 합니다.",
            HttpStatus.BAD_REQUEST
    ),
    APP_UPDATE_MIN_ABOVE_LATEST(
            "APP_UPDATE_MIN_ABOVE_LATEST",
            "최소지원버전이 최신버전보다 높을 수 없습니다.",
            HttpStatus.BAD_REQUEST
    ),
    APP_UPDATE_STORE_URL_INVALID(
            "APP_UPDATE_STORE_URL_INVALID",
            "스토어 URL은 https:// 로 시작해야 합니다.",
            HttpStatus.BAD_REQUEST
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
