package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.PatternWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@SuppressWarnings("MagicConstant")
public class PatternWithErrorCodeValidator implements ConstraintValidator<PatternWithErrorCode, CharSequence> {

    private String errorCodeName;
    private Pattern pattern;

    @Override
    public void initialize(PatternWithErrorCode constraintAnnotation) {
        this.errorCodeName = constraintAnnotation.value();
        int flags = 0;
        for (PatternWithErrorCode.Flag flag : constraintAnnotation.flags()) {
            flags |= flag.getValue();
        }
        try {
            this.pattern = Pattern.compile(constraintAnnotation.regexp(), flags);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid regular expression: " + constraintAnnotation.regexp(), e
            );
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        if (!pattern.matcher(value).matches()) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(errorCodeName)
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
