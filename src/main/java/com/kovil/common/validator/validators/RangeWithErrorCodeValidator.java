package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.RangeWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RangeWithErrorCodeValidator implements ConstraintValidator<RangeWithErrorCode, Number> {

    private String errorCodeName;
    private long min;
    private long max;

    @Override
    public void initialize(RangeWithErrorCode annotation) {
        this.errorCodeName = annotation.value();
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(Number value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        long v = value.longValue();
        if (v < min || v > max) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
