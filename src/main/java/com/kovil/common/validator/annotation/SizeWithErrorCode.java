package com.kovil.common.validator.annotation;

import com.kovil.common.validator.validators.SizeWithErrorCodeCollectionValidator;
import com.kovil.common.validator.validators.SizeWithErrorCodeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {
        SizeWithErrorCodeValidator.class,
        SizeWithErrorCodeCollectionValidator.class
})
public @interface SizeWithErrorCode {
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String value();

    int min() default 0;

    int max() default Integer.MAX_VALUE;
}
