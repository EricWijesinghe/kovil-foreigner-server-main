package com.kovil.infra.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// FakeOcrClient의 고정 샘플에 대응하는 고정 응답이다. 둘이 짝을 이뤄야
// 벤더 없이도 "업로드 → 초안" 흐름 전체를 눌러볼 수 있다.
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "fake", matchIfMissing = true)
public class FakeLlmClient implements LlmClient {

    private static final String SAMPLE = """
            {"tenantName":"NGUYEN VAN A",
             "roadAddress":"서울특별시 광진구 아차산로 200",
             "detailAddress":"301동 1502호",
             "monthlyRent":700000,
             "paymentDay":25,
             "startDate":"2026-10-01",
             "endDate":"2027-09-30",
             "bankNameRaw":"신한은행",
             "accountNumber":"110123456789",
             "accountHolder":"픽셀네트웍스",
             "specialTerms":"반려동물 사육을 허용한다. 시설 파손 시 임차인이 원상복구한다."}
            """;

    @Override
    public String complete(String prompt) {
        log.info("[FakeLlm] 프롬프트 {}자 수신 — 고정 초안을 반환한다", prompt.length());
        return SAMPLE;
    }
}
