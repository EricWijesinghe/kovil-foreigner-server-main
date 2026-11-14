package com.kovil.infra.ocr;

// ★ 바이트를 그대로 들고 다닌다. 파일 원장(files)을 거치지 않는 유일한 경로다 —
// 계약서는 저장하지 않기로 확정됐다 — 스토리지에 닿으면 안 된다.
public record OcrDocument(byte[] content, String mimeType, String fileName) {
}
