package com.kovil.domain.auth.service;

import com.kovil.domain.user.service.UserService;
import com.kovil.infra.oauth.apple.AppleAccountEvent;
import com.kovil.infra.oauth.apple.AppleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Apple 서버 간 알림. 사용자가 Apple 쪽에서 연결을 끊거나 Apple 계정을 지우면 온다.
//
// ★ 연결(user_auth)은 남기고 세션과 저장한 Apple 토큰만 지운다(PO 확정 2026-11-10). 연결을 풀면 그 사람이
//   다시 Apple 로 왔을 때 새 신원이 되고, 번호가 옛 계정에 묶여 있어 DUPLICATE_PHONE 으로 막다른 길이 된다.
//   같은 sub 로 다시 오면 원래 계정으로 로그인된다.
// ★ 트랜잭션을 걸지 않는다. 서명 검증(Apple 공개키)을 기다리는 동안 DB 커넥션을 쥐지 않게, DB 일은 UserService 에서만 한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AppleAccountEventService {

    private final UserService userService;
    private final AppleIdTokenVerifier idTokenVerifier;

    // 같은 알림이 다시 와도 결과가 같다. 이미 끊긴 세션 · 비운 칸을 다시 비울 뿐이다.
    public void handle(String payload) {
        AppleAccountEvent event = idTokenVerifier.verifyEvent(payload);
        if (event.endsConsent()) {
            boolean found = userService.endAppleSession(event.sub());
            log.info("[oauth] Apple 알림 type={} 연결된 계정={}", event.type(), found);
            return;
        }
        // email-disabled · email-enabled 는 기록만 한다. 릴레이 주소로 메일을 보내지 않아 바꿀 것이 없다.
        log.info("[oauth] Apple 알림 type={}", event.type());
    }
}
