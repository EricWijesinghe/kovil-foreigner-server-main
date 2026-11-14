package com.kovil.domain.auth.service;

import com.kovil.common.ratelimit.RateLimitException;
import com.kovil.common.ratelimit.RateLimitPolicy;
import com.kovil.common.ratelimit.RateLimitStore;
import com.kovil.domain.auth.config.PhoneVerificationProperties;
import com.kovil.domain.auth.dto.response.PhoneSendResponse;
import com.kovil.domain.auth.dto.response.PhoneVerifyResponse;
import com.kovil.domain.auth.entity.PhoneVerification;
import com.kovil.domain.auth.enums.VerificationPurpose;
import com.kovil.domain.auth.exception.PhoneVerificationException;
import com.kovil.domain.auth.repository.PhoneVerificationRepository;
import com.kovil.domain.auth.store.PhoneVerificationStore;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.crypto.Hasher;
import com.kovil.infra.sms.SmsMessage;
import com.kovil.infra.sms.SmsSender;
import com.kovil.common.tx.AfterCommitRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_CODE_INVALID;
import static com.kovil.domain.auth.exception.AuthErrorCode.VERIFICATION_RESEND_TOO_SOON;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

    private final PhoneNumberNormalizer phoneNumberNormalizer;
    private final VerificationCodeGenerator codeGenerator;
    private final PhoneVerificationStore store;
    private final PhoneVerificationRepository repository;
    private final PhoneVerificationProperties properties;
    private final RateLimitStore rateLimitStore;
    private final Encryptor encryptor;
    private final Hasher hasher;
    private final SmsSender smsSender;
    private final Clock clock;

    public PhoneSendResponse send(String rawPhone, VerificationPurpose purpose) {
        String phone = phoneNumberNormalizer.normalize(rawPhone);
        String phoneHash = hasher.hash(phone);

        // 쿨다운이 먼저다. 번호축 한도를 소비하기 전에 막아야 "연타로 한도만 태우는" 흐름이 생기지 않는다.
        if (store.isCoolingDown(phoneHash)) {
            throw new PhoneVerificationException(VERIFICATION_RESEND_TOO_SOON);
        }
        // 번호축 정책은 키가 요청 본문에서만 나오므로 인터셉터가 다루지 못한다(MANUAL).
        consumeOrThrow(RateLimitPolicy.PHONE_VERIFICATION_SEND, phoneHash);

        String code = codeGenerator.generate();
        store.saveCode(phoneHash, purpose, code, properties.codeTtl());
        store.startCooldown(phoneHash, properties.resendCooldown());

        smsSender.send(new SmsMessage(phone, text(code)));

        return new PhoneSendResponse(
                properties.codeTtl().toSeconds(), properties.resendCooldown().toSeconds());
    }

    @Transactional
    public PhoneVerifyResponse verify(String rawPhone, String inputCode, VerificationPurpose purpose) {
        String phone = phoneNumberNormalizer.normalize(rawPhone);
        String phoneHash = hasher.hash(phone);

        consumeOrThrow(RateLimitPolicy.PHONE_VERIFICATION_VERIFY, phoneHash);

        String issued = store.findCode(phoneHash, purpose)
                .orElseThrow(() -> new PhoneVerificationException(VERIFICATION_CODE_INVALID));

        long attempts = store.increaseAttempt(phoneHash, purpose, properties.codeTtl());
        if (attempts > properties.maxAttempts()) {
            store.discardCode(phoneHash, purpose);
            throw new PhoneVerificationException(VERIFICATION_CODE_INVALID);
        }

        // 길이·내용이 노출되지 않도록 상수 시간 비교를 쓴다.
        if (!constantTimeEquals(issued, inputCode)) {
            throw new PhoneVerificationException(VERIFICATION_CODE_INVALID);
        }

        // 성공 폐기는 커밋 이후다 — 트랜잭션 안에서 지우면 아래 save·커밋이 실패해 롤백될 때
        // 코드만 사라져, 올바른 코드를 넣은 사용자가 SMS 발송(과금)부터 다시 해야 한다.
        // 트레이드오프: 커밋까지의 수 ms 동안 같은 코드 재통과 창이 생기지만, 그 결과는 토큰
        // 이중 발급이고 하류(번호 유니크·토큰 소비 판정)가 막는다.
        // ★ 위 시도 초과 폐기(discardCode)는 옮기면 안 된다 — 그쪽은 예외로 롤백되는 경로라
        //   afterCommit 이 실행되지 않아 징벌 폐기가 유실된다. 이 비대칭은 의도다.
        AfterCommitRunner.run("phone-code", () -> store.discardCode(phoneHash, purpose));

        PhoneVerification verification = repository.save(PhoneVerification.builder()
                .phone(encryptor.encrypt(phone))
                .phoneHash(phoneHash)
                .purpose(purpose)
                .verifiedAt(LocalDateTime.now(clock))
                .build());

        String token = UUID.randomUUID().toString().replace("-", "");
        store.saveToken(token, verification.getId(), properties.tokenTtl());

        return new PhoneVerifyResponse(token, properties.tokenTtl().toSeconds());
    }

    private void consumeOrThrow(RateLimitPolicy policy, String phoneHash) {
        RateLimitStore.RateLimitResult result = rateLimitStore.tryConsume(policy, phoneHash);
        if (!result.allowed()) {
            throw new RateLimitException(result.retryAfterSeconds());
        }
    }

    // 수신자는 한국어를 못 읽는 외국인이다 — 본문은 영어로 나간다(PO 확정 2026-08-22).
    // ASCII 로만 쓰는 것도 의도다: 비라틴 문자가 한 글자라도 섞이면 SMS(90바이트)가 LMS 로
    // 승격되거나 통신사 구간에서 인코딩이 깨져 **인증번호 자체가 안 보이는** 사고가 난다.
    // 만료 시간은 설정값(code-ttl)에서 그대로 읽는다 — 문구와 실제가 어긋나면 문의가 늘어난다.
    private String text(String code) {
        return "[KOVIL] Your verification code is " + code
                + ". It expires in " + validity() + ".";
    }

    private String validity() {
        long minutes = properties.codeTtl().toMinutes();
        if (minutes < 1) {
            return properties.codeTtl().toSeconds() + " seconds";
        }
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
