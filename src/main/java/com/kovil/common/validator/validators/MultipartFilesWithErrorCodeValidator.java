package com.kovil.common.validator.validators;

import com.kovil.common.validator.annotation.MultipartFilesWithErrorCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

public class MultipartFilesWithErrorCodeValidator
        implements ConstraintValidator<MultipartFilesWithErrorCode, List<MultipartFile>> {

    private String emptyErrorCode;
    private int maxCount;
    private String maxCountErrorCode;
    private long maxFileSizeBytes;
    private String fileTooLargeErrorCode;
    private Set<String> allowedMimeTypes;
    private String unsupportedTypeErrorCode;

    @Override
    public void initialize(MultipartFilesWithErrorCode annotation) {
        this.emptyErrorCode = annotation.emptyErrorCode();
        this.maxCount = annotation.maxCount();
        this.maxCountErrorCode = annotation.maxCountErrorCode();
        this.maxFileSizeBytes = annotation.maxFileSizeBytes();
        this.fileTooLargeErrorCode = annotation.fileTooLargeErrorCode();
        this.allowedMimeTypes = Set.of(annotation.allowedMimeTypes());
        this.unsupportedTypeErrorCode = annotation.unsupportedTypeErrorCode();
    }

    @Override
    public boolean isValid(List<MultipartFile> files, ConstraintValidatorContext context) {
        if (files == null || files.isEmpty()) {
            return reject(context, emptyErrorCode);
        }
        if (files.size() > maxCount) {
            return reject(context, maxCountErrorCode);
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                return reject(context, emptyErrorCode);
            }
            if (file.getSize() > maxFileSizeBytes) {
                return reject(context, fileTooLargeErrorCode);
            }
            String contentType = file.getContentType();
            if (contentType == null || !allowedMimeTypes.contains(contentType)) {
                return reject(context, unsupportedTypeErrorCode);
            }
        }
        return true;
    }

    private boolean reject(ConstraintValidatorContext context, String errorCodeName) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(errorCodeName).addConstraintViolation();
        return false;
    }
}
