package com.kovil.infra.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// 크리덴셜 없이 부팅·테스트가 가능해야 하므로 이쪽이 기본값이다.
// 결정적 고정 응답을 돌려준다 — 파싱 파이프라인 전체를 벤더 없이 확인할 수 있어야 한다.
@Slf4j
@Component
@ConditionalOnProperty(name = "ocr.provider", havingValue = "fake", matchIfMissing = true)
public class FakeOcrClient implements OcrClient {

    private static final String SAMPLE = """
            부동산 임대차 계약서
            소재지: 서울특별시 광진구 아차산로 200 301동 1502호
            임차인: NGUYEN VAN A
            차임(월세): 금 칠십만원정 (700,000원)
            차임 지급일: 매월 25일
            임대차 기간: 2026년 10월 1일부터 2027년 9월 30일까지
            입금계좌: 신한은행 110-123-456789 (예금주: 픽셀네트웍스)
            특약사항: 반려동물 사육을 허용한다. 시설 파손 시 임차인이 원상복구한다.
            """;

    @Override
    public OcrResult extractText(List<OcrDocument> pages) {
        log.info("[FakeOcr] {}장 수신 — 고정 샘플을 반환한다", pages.size());
        return OcrResult.of(SAMPLE);
    }
}
