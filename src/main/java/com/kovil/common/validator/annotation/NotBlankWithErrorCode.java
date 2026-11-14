package com.kovil.common.validator.annotation;

import com.kovil.common.validator.validators.NotBlankWithErrorCodeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotBlankWithErrorCodeValidator.class)
@Documented
public @interface NotBlankWithErrorCode {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String value();

}
