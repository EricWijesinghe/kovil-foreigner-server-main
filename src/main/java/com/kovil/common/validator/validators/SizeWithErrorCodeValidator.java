package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.SizeWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SizeWithErrorCodeValidator implements ConstraintValidator<SizeWithErrorCode, String> {

    private String errorCodeName;
    private int min;
    private int max;

    @Override
    public void initialize(SizeWithErrorCode annotation) {
        this.errorCodeName = annotation.value();
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        int length = value.length();

        if (length < min || length > max) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
