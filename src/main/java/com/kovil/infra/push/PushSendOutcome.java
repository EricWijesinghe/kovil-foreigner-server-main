package com.kovil.infra.push;

// INVALID_TOKEN과 TEMPORARY_FAILURE를 갈라 두는 이유는 대응이 정반대이기 때문이다.
// 앞은 토큰을 지워야 하고, 뒤는 그대로 두고 다시 보내야 한다.
public enum PushSendOutcome {

    SUCCESS,
    INVALID_TOKEN,
    TEMPORARY_FAILURE
}
