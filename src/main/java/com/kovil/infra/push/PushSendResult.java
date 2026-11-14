package com.kovil.infra.push;

public record PushSendResult(PushSendOutcome outcome, String messageId, String detail) {

    public static PushSendResult success(String messageId) {
        return new PushSendResult(PushSendOutcome.SUCCESS, messageId, null);
    }

    // 기기가 앱을 지웠거나 토큰이 만료됐다 — 지워야 한다.
    public static PushSendResult invalidToken(String detail) {
        return new PushSendResult(PushSendOutcome.INVALID_TOKEN, null, detail);
    }

    // 벤더 장애·네트워크 — 토큰은 멀쩡하므로 지우면 안 된다.
    public static PushSendResult temporaryFailure(String detail) {
        return new PushSendResult(PushSendOutcome.TEMPORARY_FAILURE, null, detail);
    }

    public boolean isSuccess() {
        return outcome == PushSendOutcome.SUCCESS;
    }

    public boolean isTokenInvalid() {
        return outcome == PushSendOutcome.INVALID_TOKEN;
    }
}
