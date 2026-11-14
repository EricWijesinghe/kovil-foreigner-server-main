package com.kovil.infra.ocr;

import java.util.List;

public interface OcrClient {

    OcrResult extractText(List<OcrDocument> pages);
}
