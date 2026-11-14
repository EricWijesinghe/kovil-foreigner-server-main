package com.kovil.common.validator.annotation;

import com.kovil.common.validator.validators.PatternWithErrorCodeValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import lombok.Getter;

import java.lang.annotation.*;
import java.util.regex.Pattern;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Constraint(validatedBy = PatternWithErrorCodeValidator.class)
public @interface PatternWithErrorCode {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    String value();

    String regexp();

    Flag[] flags() default {};

    @Getter
    enum Flag {
        UNIX_LINES(Pattern.UNIX_LINES),
        CASE_INSENSITIVE(Pattern.CASE_INSENSITIVE),
        COMMENTS(Pattern.COMMENTS),
        MULTILINE(Pattern.MULTILINE),
        DOTALL(Pattern.DOTALL),
        UNICODE_CASE(Pattern.UNICODE_CASE),
        CANON_EQ(Pattern.CANON_EQ);

        private final int value;

        Flag(int value) {
            this.value = value;
        }
    }
}
