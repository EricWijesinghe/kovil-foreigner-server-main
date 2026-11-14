package com.kovil.domain.rentalrequest.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RentalRequestErrorCode implements ErrorCode {

    // FR-301 — 게이트. 클라이언트는 이 코드를 보고 인증 화면으로 보낸다.
    VERIFICATION_REQUIRED("Foreigner verification is required before requesting a rental.", HttpStatus.FORBIDDEN),

    RENTAL_REQUEST_NOT_FOUND("Rental request not found.", HttpStatus.NOT_FOUND),
    REQUEST_NOT_EDITABLE("This request can no longer be changed.", HttpStatus.CONFLICT),
    // 대기가 아니라 잡을 수 없다 — 유학생이 취소했거나 이미 종료된 요청. 다른 사무소가 먼저 잡은 경우는 아래 ALREADY_CLAIMED 다.
    REQUEST_NOT_OPEN("This request is already being handled or closed.", HttpStatus.CONFLICT),
    RENTAL_REQUEST_LIMIT_EXCEEDED("You already have an ongoing rental request.", HttpStatus.CONFLICT),

    // 선착순에서 진 쪽. 동시에 누른 두 사무소 중 늦은 쪽이 받는다 — 목록을 새로고침하게 한다.
    RENTAL_REQUEST_ALREADY_CLAIMED("Another office has already taken this request.", HttpStatus.CONFLICT),
    // 사무소당 진행 중 요청 상한(설정값). 맡은 요청을 종료하거나 놓아야 새로 잡을 수 있다.
    RENTAL_REQUEST_CLAIM_LIMIT("Your office has reached the limit of requests in progress. Close or release a request first.",
            HttpStatus.CONFLICT),
    // 놓기 · 종료는 진행 중일 때만 — 그사이 유학생이 취소했거나 이미 종료 · 대기로 돌아갔다.
    RENTAL_REQUEST_NOT_IN_PROGRESS("This request is no longer in progress.", HttpStatus.CONFLICT),
    RENTAL_REQUEST_OUTCOME_REQUIRED("Choose how the request ended.", HttpStatus.BAD_REQUEST),
    // 잡기 · 놓기 · 종료 때 DB 로 다시 본 공인중개사 부문이 승인 상태가 아니다(정지 등) — 토큰이 낡았다. 다시 로그인하면 403 이 된다.
    RENTAL_REQUEST_OFFICE_NOT_ACTIVE("Your office is not approved to handle rental requests.", HttpStatus.FORBIDDEN),

    REGION_REQUIRED("Select at least one area.", HttpStatus.BAD_REQUEST),
    REGION_INVALID_LEVEL("Select a district, not a province.", HttpStatus.BAD_REQUEST),
    REGION_NOT_FOUND("That area is not available.", HttpStatus.BAD_REQUEST),

    OPTION_NOT_FOUND("That option is not available.", HttpStatus.BAD_REQUEST),
    OPTION_SELECTION_INVALID("Only one choice is allowed in this group.", HttpStatus.BAD_REQUEST),
    OPTION_ANSWER_REQUIRED("Please answer this question.", HttpStatus.BAD_REQUEST),
    OPTION_EXTRA_REQUIRED("Please fill in the additional field.", HttpStatus.BAD_REQUEST),
    OPTION_EXTRA_INVALID("The additional field has an invalid value.", HttpStatus.BAD_REQUEST),
    // DATE 질문의 과거 거부(V16) — "입주 희망일" 류. 어떤 질문인지는 화면이 그 자리에서 보여준다.
    OPTION_DATE_IN_PAST("Past dates cannot be selected for this question.", HttpStatus.BAD_REQUEST),
    OPTION_TEXT_LIMIT_INVALID("Text length limit must be between 1 and 255.", HttpStatus.BAD_REQUEST),
    OPTION_NUMBER_RANGE_INVALID("Number range is invalid: min cannot exceed max.", HttpStatus.BAD_REQUEST),
    OPTION_CONDITION_INVALID("A conditional question cannot depend on another conditional question.",
            HttpStatus.BAD_REQUEST),

    BUDGET_RANGE_INVALID("The minimum must not be greater than the maximum.", HttpStatus.BAD_REQUEST),
    MOVE_IN_DATE_IN_PAST("The move-in date must be today or later.", HttpStatus.BAD_REQUEST);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
