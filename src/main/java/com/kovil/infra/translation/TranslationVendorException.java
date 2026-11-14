package com.kovil.infra.translation;

import lombok.Getter;

/*
 * ★ ErrorCode 를 만들지 않는다. 번역 실패는 **절대 응답으로 나가지 않기** 때문이다 —
 *   어댑터가 삼키고 원문을 돌려준다. ErrorCode 는 HTTP 응답이 될 값에만 붙인다.
 *   이 예외가 사는 구간은 Feign 디코더 ~ 어댑터 catch 뿐이고, 남는 것은 로그 한 줄이다.
 */
@Getter
public class TranslationVendorException extends RuntimeException {

    public enum Reason {
        // 크리덴셜과 쿼터를 갈라 둔다 — 전자는 사람이 키를 고쳐야 하고 후자는 기다리면 풀린다.
        UNAUTHORIZED,
        RATE_LIMITED,
        PROVIDER_ERROR
    }

    private final Reason reason;

    public TranslationVendorException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
