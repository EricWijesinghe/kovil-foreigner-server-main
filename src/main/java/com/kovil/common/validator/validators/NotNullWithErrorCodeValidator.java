package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.NotNullWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotNullWithErrorCodeValidator implements ConstraintValidator<NotNullWithErrorCode, Object> {

    private String errorCodeName;

    @Override
    public void initialize(NotNullWithErrorCode annotation) {
        this.errorCodeName = annotation.value();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
