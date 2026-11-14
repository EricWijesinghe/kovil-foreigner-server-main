package com.kovil.infra.naver.ocr;

import com.kovil.infra.ocr.exception.OcrException;
import feign.Response;
import feign.codec.ErrorDecoder;

import static com.kovil.infra.ocr.exception.OcrErrorCode.OCR_PROVIDER_ERROR;
import static com.kovil.infra.ocr.exception.OcrErrorCode.OCR_UNAUTHORIZED;
import static com.kovil.infra.ocr.exception.OcrErrorCode.OCR_UNSUPPORTED_FILE_TYPE;

// 벤더 상태코드를 우리 코드로 옮긴다. 401·403을 400과 뭉치면
// "크리덴셜이 틀렸다"를 "파일이 이상하다"로 오진하게 된다.
public class ClovaOcrErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        return switch (response.status()) {
            case 400 -> new OcrException(OCR_UNSUPPORTED_FILE_TYPE);
            case 401, 403 -> new OcrException(OCR_UNAUTHORIZED);
            default -> new OcrException(OCR_PROVIDER_ERROR);
        };
    }
}
