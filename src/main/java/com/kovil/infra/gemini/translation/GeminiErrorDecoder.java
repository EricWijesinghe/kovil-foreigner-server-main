package com.kovil.infra.gemini.translation;

import com.kovil.infra.translation.TranslationVendorException;
import feign.Response;
import feign.codec.ErrorDecoder;

import static com.kovil.infra.translation.TranslationVendorException.Reason.PROVIDER_ERROR;
import static com.kovil.infra.translation.TranslationVendorException.Reason.RATE_LIMITED;
import static com.kovil.infra.translation.TranslationVendorException.Reason.UNAUTHORIZED;

public class GeminiErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return switch (response.status()) {
            case 401, 403 -> new TranslationVendorException(UNAUTHORIZED);
            case 429, 503 -> new TranslationVendorException(RATE_LIMITED);
            default -> new TranslationVendorException(PROVIDER_ERROR);
        };
    }
}
