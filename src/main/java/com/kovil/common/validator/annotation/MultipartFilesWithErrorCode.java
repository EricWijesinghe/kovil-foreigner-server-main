package com.kovil.common.validator.annotation;

import com.kovil.common.validator.validators.MultipartFilesWithErrorCodeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MultipartFilesWithErrorCodeValidator.class)
public @interface MultipartFilesWithErrorCode {
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String emptyErrorCode();

    int maxCount();

    String maxCountErrorCode();

    long maxFileSizeBytes();

    String fileTooLargeErrorCode();

    String[] allowedMimeTypes();

    String unsupportedTypeErrorCode();
}
