package com.kovil.domain.community.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommunityErrorCode implements ErrorCode {

    CATEGORY_NOT_FOUND("That board is not available.", HttpStatus.NOT_FOUND),
    // 글이 하나라도 있으면 익명 설정 변경 금지 — 과거 익명 글의 작성자를 소급 노출시키는 사고다.
    CATEGORY_HAS_POSTS("A board with posts cannot change its anonymity.", HttpStatus.CONFLICT),

    POST_NOT_FOUND("Post not found.", HttpStatus.NOT_FOUND),
    POST_TITLE_REQUIRED("Enter a title.", HttpStatus.BAD_REQUEST),
    POST_BODY_REQUIRED("Enter the content.", HttpStatus.BAD_REQUEST),
    // 공개 콘텐츠라 존재는 숨기지 않는다 — 권한만 없다.
    NOT_CONTENT_OWNER("Only the author can do this.", HttpStatus.FORBIDDEN),

    TAG_LIMIT_EXCEEDED("You can add up to 10 tags.", HttpStatus.BAD_REQUEST),
    TAG_INVALID("Each tag must be 1-20 characters.", HttpStatus.BAD_REQUEST),
    TAG_BANNED("That tag cannot be used.", HttpStatus.BAD_REQUEST),
    IMAGE_LIMIT_EXCEEDED("You can attach up to 5 images.", HttpStatus.BAD_REQUEST),

    COMMENT_NOT_FOUND("Comment not found.", HttpStatus.NOT_FOUND),
    COMMENT_BODY_REQUIRED("Enter the comment.", HttpStatus.BAD_REQUEST),
    COMMENT_DEPTH_EXCEEDED("Replies to a reply are not allowed.", HttpStatus.BAD_REQUEST),
    COMMENT_PARENT_MISMATCH("That comment belongs to another post.", HttpStatus.BAD_REQUEST),

    REPORT_REASON_REQUIRED("Select a reason.", HttpStatus.BAD_REQUEST),
    REPORT_DUPLICATE("You already reported this.", HttpStatus.CONFLICT),
    REPORT_NOT_FOUND("Report not found.", HttpStatus.NOT_FOUND),
    REPORT_ALREADY_HANDLED("This report has already been handled.", HttpStatus.CONFLICT),
    // 자기 글·댓글을 지목한 차단 — 자기 자신은 차단할 수 없다.
    SELF_BLOCK("You cannot block yourself.", HttpStatus.BAD_REQUEST);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
