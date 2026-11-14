package com.kovil.domain.verification.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum VerificationErrorCode implements ErrorCode {

    // 매트릭스 미구성 신분으로 서류 0장이 통과되면 "사람이 검증한 문서" 제1 불변식이 뚫린다.
    VERIFICATION_REQUIREMENTS_NOT_READY(
            "VERIFICATION_REQUIREMENTS_NOT_READY",
            "이 체류 자격의 서류 요건이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.",
            HttpStatus.CONFLICT
    ),
    RESIDENT_STATUS_NOT_FOUND(
            "RESIDENT_STATUS_NOT_FOUND",
            "선택할 수 없는 신분입니다.",
            HttpStatus.NOT_FOUND
    ),
    // 심사 중에는 새로 내지 못한다. 반려된 뒤에는 새 행으로 다시 낼 수 있다(FR-207).
    VERIFICATION_PENDING(
            "VERIFICATION_PENDING",
            "심사가 진행 중입니다. 결과를 기다려 주세요.",
            HttpStatus.CONFLICT
    ),
    VERIFICATION_DOCS_MISSING(
            "VERIFICATION_DOCS_MISSING",
            "필수 서류가 빠졌습니다.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_DOC_NOT_REQUIRED(
            "VERIFICATION_DOC_NOT_REQUIRED",
            "요청하지 않은 서류가 포함되어 있습니다.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_DOC_TOO_MANY(
            "VERIFICATION_DOC_TOO_MANY",
            "서류당 허용 장수를 넘었습니다.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_FILE_INVALID(
            "VERIFICATION_FILE_INVALID",
            "제출한 파일을 사용할 수 없습니다. 다시 업로드해 주세요.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_NOT_FOUND(
            "VERIFICATION_NOT_FOUND",
            "인증 신청 내역이 없습니다.",
            HttpStatus.NOT_FOUND
    ),
    // 이미 심사된 건이다. 다시 심사하면 감사 기록이 덮어씌워진다.
    VERIFICATION_NOT_PENDING(
            "VERIFICATION_NOT_PENDING",
            "이미 심사가 끝난 신청입니다.",
            HttpStatus.CONFLICT
    ),
    REJECT_REASON_REQUIRED(
            "REJECT_REASON_REQUIRED",
            "반려 사유는 필수입니다.",
            HttpStatus.BAD_REQUEST
    ),
    VERIFICATION_EXPIRY_IN_PAST(
            "VERIFICATION_EXPIRY_IN_PAST",
            "만료일은 오늘 이후여야 합니다.",
            HttpStatus.BAD_REQUEST
    ),
    // 무효화는 승인된 건만 대상이다. 반려·대기 건은 되돌릴 승인이 없다.
    VERIFICATION_NOT_APPROVED(
            "VERIFICATION_NOT_APPROVED",
            "승인된 인증만 무효화할 수 있습니다.",
            HttpStatus.CONFLICT
    ),
    REVOKE_REASON_REQUIRED(
            "REVOKE_REASON_REQUIRED",
            "무효화 사유는 필수입니다.",
            HttpStatus.BAD_REQUEST
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
