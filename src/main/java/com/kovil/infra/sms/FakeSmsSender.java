package com.kovil.infra.sms;

import com.kovil.common.util.MaskingUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 개발·테스트용. 크리덴셜 없이 부팅·테스트가 가능해야 하므로 이쪽이 기본값이다.
// ⚠ 본문(인증번호 포함)을 로그에 남긴다 — 개발 편의를 위한 것이고, sms.provider=solapi인
// 운영에서는 이 빈 자체가 생성되지 않는다.
@Slf4j
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "fake", matchIfMissing = true)
public class FakeSmsSender implements SmsSender {

    @Override
    public void send(SmsMessage message) {
        log.info("[FakeSms] to={} text={}", MaskingUtil.maskPhone(message.toPhone()), message.text());
    }
}
