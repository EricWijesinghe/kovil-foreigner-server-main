package com.kovil.infra.ocr;

public record OcrResult(String rawText) {

    public static OcrResult of(String rawText) {
        return new OcrResult(rawText);
    }
}
