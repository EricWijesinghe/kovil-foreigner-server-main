package com.kovil.common.validator.annotation;

import com.kovil.common.validator.validators.RangeWithErrorCodeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = RangeWithErrorCodeValidator.class)
public @interface RangeWithErrorCode {
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String value();

    long min() default Long.MIN_VALUE;

    long max() default Long.MAX_VALUE;
}
