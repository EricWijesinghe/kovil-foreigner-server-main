package com.kovil.domain.partner.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PartnerErrorCode implements ErrorCode {

    NOT_A_PARTNER("This account is not a partner account.", HttpStatus.FORBIDDEN),

    PARTNER_DIVISION_NOT_FOUND("Partner division not found.", HttpStatus.NOT_FOUND),

    PARTNER_DIVISION_NOT_EDITABLE("A suspended division cannot be resubmitted.", HttpStatus.CONFLICT),

    PARTNER_REVIEW_NO_CHANGE("Nothing that requires review has changed. To update only the office phone number, use the phone change.", HttpStatus.BAD_REQUEST),

    PARTNER_DIVISION_CHANGED("This application has changed since you opened it. Reload and review it again.", HttpStatus.CONFLICT),

    PARTNER_DIVISION_NOT_REVIEWABLE("Only a submitted division awaiting review can be approved or rejected.", HttpStatus.CONFLICT),
    PARTNER_DIVISION_NOT_APPROVED("Only an approved division can be suspended.", HttpStatus.CONFLICT),
    PARTNER_DIVISION_NOT_SUSPENDED("Only a suspended division can be reinstated.", HttpStatus.CONFLICT),

    PARTNER_SUSPENDED_CANNOT_WITHDRAW("A partner with a suspended division cannot withdraw. Please contact customer support.", HttpStatus.CONFLICT),

    PARTNER_NOT_ACTIVE("This partner account is suspended or withdrawn.", HttpStatus.CONFLICT),

    PARTNER_LICENSE_FILE_INVALID("The registration certificate file cannot be attached.", HttpStatus.BAD_REQUEST),

    OFFICE_CHANGE_REQUEST_NOT_FOUND("Office change request not found.", HttpStatus.NOT_FOUND),

    OFFICE_CHANGE_REQUEST_CHANGED("This change request has changed since you opened it. Reload and review it again.", HttpStatus.CONFLICT),

    OFFICE_CHANGE_REQUEST_NOT_REVIEWABLE("Only a pending change request can be approved or rejected.", HttpStatus.CONFLICT),

    OFFICE_CHANGE_DIVISION_NOT_APPROVED("The office change can be approved only while the broker division is approved.", HttpStatus.CONFLICT),

    OFFICE_CHANGE_NOT_PENDING("There is no pending change request.", HttpStatus.CONFLICT);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}
