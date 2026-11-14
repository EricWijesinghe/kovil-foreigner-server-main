package com.kovil.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ErrorMessageResolver {

    private final MessageSource messageSource;

    public String resolve(ErrorCode errorCode) {
        return resolve(errorCode, LocaleContextHolder.getLocale());
    }

    // 필터 단계는 DispatcherServlet 이전이라 LocaleContextHolder가 비어 있다.
    // 그쪽 호출자는 request.getLocale()을 직접 넘긴다.
    public String resolve(ErrorCode errorCode, Locale locale) {
        return messageSource.getMessage(
                errorCode.getCode(), null, errorCode.getDefaultMessage(), locale);
    }
}
