package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.SizeWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Collection;

public class SizeWithErrorCodeCollectionValidator implements ConstraintValidator<SizeWithErrorCode, Collection<?>> {

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
    public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        int size = value.size();

        if (size < min || size > max) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
