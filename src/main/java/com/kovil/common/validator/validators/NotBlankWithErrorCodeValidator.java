package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.NotBlankWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotBlankWithErrorCodeValidator implements ConstraintValidator<NotBlankWithErrorCode, String> {

    private String errorCodeName;

    @Override
    public void initialize(NotBlankWithErrorCode annotation) {
        this.errorCodeName = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.trim().isEmpty()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
