package com.kovil.infra.naver.ocr;

import com.kovil.infra.naver.ocr.dto.request.ClovaOcrRequest;
import com.kovil.infra.naver.ocr.dto.response.ClovaOcrResponse;
import com.kovil.infra.ocr.OcrClient;
import com.kovil.infra.ocr.OcrDocument;
import com.kovil.infra.ocr.OcrResult;
import com.kovil.infra.ocr.exception.OcrException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.kovil.infra.ocr.exception.OcrErrorCode.OCR_PROVIDER_ERROR;
import static com.kovil.infra.ocr.exception.OcrErrorCode.OCR_UNSUPPORTED_FILE_TYPE;

// ⚠ 한 번도 실제로 호출된 적이 없다 — 크리덴셜이 없어 ocr.provider=fake가 기본이다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ocr.provider", havingValue = "clova")
public class ClovaOcrClientImpl implements OcrClient {

    private static final String API_VERSION = "V2";
    private static final String LANG = "ko";

    private final ClovaOcrFeignClient feignClient;

    // 장마다 따로 호출하고 텍스트를 이어 붙인다. 계약서는 여러 장이 한 문서다.
    @Override
    public OcrResult extractText(List<OcrDocument> pages) {
        StringBuilder combined = new StringBuilder();
        for (OcrDocument page : pages) {
            combined.append(joinFields(feignClient.recognize(buildRequest(page))));
            combined.append("\n");
        }
        return OcrResult.of(combined.toString().strip());
    }

    private ClovaOcrRequest buildRequest(OcrDocument page) {
        ClovaOcrRequest.Image image = new ClovaOcrRequest.Image(
                resolveFormat(page.mimeType()),
                page.fileName() != null ? page.fileName() : "page",
                Base64.getEncoder().encodeToString(page.content()));

        return new ClovaOcrRequest(API_VERSION, UUID.randomUUID().toString(),
                System.currentTimeMillis(), LANG, List.of(image));
    }

    private String resolveFormat(String mimeType) {
        if (mimeType == null) {
            throw new OcrException(OCR_UNSUPPORTED_FILE_TYPE);
        }
        return switch (mimeType) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/tiff" -> "tiff";
            case "application/pdf" -> "pdf";
            default -> throw new OcrException(OCR_UNSUPPORTED_FILE_TYPE);
        };
    }

    // lineBreak를 살려야 "차임 지급일: 매월 25일" 같은 줄 단위 의미가 LLM에 전달된다.
    private String joinFields(ClovaOcrResponse response) {
        if (response == null || response.images() == null || response.images().isEmpty()) {
            throw new OcrException(OCR_PROVIDER_ERROR);
        }
        StringBuilder pageText = new StringBuilder();
        for (ClovaOcrResponse.Image image : response.images()) {
            if (image.fields() == null) {
                continue;
            }
            for (ClovaOcrResponse.Field field : image.fields()) {
                pageText.append(field.inferText());
                pageText.append(field.lineBreak() ? "\n" : " ");
            }
        }
        return pageText.toString();
    }
}
