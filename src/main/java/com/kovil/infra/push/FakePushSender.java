package com.kovil.infra.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 크리덴셜 없이 부팅·테스트가 가능해야 하므로 이쪽이 기본값이다.
//
// **발송 문구를 통째로 로그에 찍는다.** 지금은 실제 기기로 나가지 않으므로
// 터미널이 유일한 확인 창구다 — 언어별 문구가 맞는지, 치환 값이 들어갔는지를 눈으로 본다.
// 한 줄로 흘리면 로그 사이에 묻히니 블록으로 띄운다.
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.push.provider", havingValue = "fake", matchIfMissing = true)
public class FakePushSender implements PushSender {

    @Override
    public PushSendResult send(PushMessage message) {
        String messageId = UUID.randomUUID().toString();

        log.info("""

                        ┌─ PUSH (fake) ─────────────────────────────────────────
                        │ to     : {}
                        │ title  : {}
                        │ body   : {}
                        │ data   : {}
                        └───────────────────────────────────────────────────────""",
                mask(message.token()),
                message.title(),
                message.body(),
                message.data().isEmpty() ? "-" : message.data());

        return PushSendResult.success(messageId);
    }

    // 토큰 전체를 찍을 이유가 없다 — 어느 기기인지만 구분되면 된다.
    private String mask(String token) {
        if (token == null || token.length() <= 12) {
            return token;
        }
        return token.substring(0, 8) + "…" + token.substring(token.length() - 4);
    }
}
