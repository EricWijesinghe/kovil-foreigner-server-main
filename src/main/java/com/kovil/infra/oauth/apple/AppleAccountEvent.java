package com.kovil.infra.oauth.apple;

// Apple 서버 간 알림의 events 한 건. 서명을 통과한 값만 이 꼴이 된다.
public record AppleAccountEvent(String type, String sub) {

    // 문서는 account-deleted 라 적고, 실제로는 account-delete 로 온다는 보고가 있어 둘 다 받는다.
    public boolean endsConsent() {
        return "consent-revoked".equals(type) || "account-delete".equals(type) || "account-deleted".equals(type);
    }
}
