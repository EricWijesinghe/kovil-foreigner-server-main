package com.kovil.common.exception;

import lombok.Getter;

import java.text.MessageFormat;

@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object[] args;

    public CustomException(ErrorCode errorCode) {
        this(errorCode, (Object[]) null);
    }

    public CustomException(ErrorCode errorCode, Object... args) {
        super(formatMessage(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    private static String formatMessage(ErrorCode errorCode, Object... args) {
        if (args == null || args.length == 0) {
            return errorCode.getDefaultMessage();
        }
        String message = errorCode.getDefaultMessage();
        if (!message.matches(".*\\{\\d+}.*")) {
            return message;
        }
        return MessageFormat.format(message, args);
    }
}
