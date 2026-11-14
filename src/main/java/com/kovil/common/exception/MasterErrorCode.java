package com.kovil.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 마스터 데이터 관리는 축이 여덟이어도 한 관심사다 — 에러 사전도 하나로 쓴다.
@Getter
@RequiredArgsConstructor
public enum MasterErrorCode implements ErrorCode {

    MASTER_NOT_FOUND("That item could not be found.", HttpStatus.NOT_FOUND),
    MASTER_DUPLICATE("That code or pair already exists.", HttpStatus.CONFLICT),
    // 매트릭스·트리가 지목한 참조(신분·문서·부모·언어)가 없다.
    MASTER_REFERENCE_NOT_FOUND("A referenced item could not be found.", HttpStatus.BAD_REQUEST),
    // 재정렬 ids가 스코프의 완전한 순열이 아니다 — 대개 다른 관리자가 방금 행을 추가한 경합이다.
    MASTER_ORDER_SCOPE_MISMATCH("The list has changed. Reload and try again.", HttpStatus.CONFLICT);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
