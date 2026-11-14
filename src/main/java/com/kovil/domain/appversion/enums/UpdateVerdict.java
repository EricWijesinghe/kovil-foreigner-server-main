package com.kovil.domain.appversion.enums;

/**
 * 앱 버전 판정 결과. 판정은 서버가 하고, 앱은 이 값에 따라서만 동작한다.
 * (비교 로직을 앱에 두면 이미 배포된 구버전 앱의 판정을 고칠 수 없다)
 */
public enum UpdateVerdict {
    /** 최소 지원 버전 미만. 닫을 수 없는 전체 화면으로 스토어 이동만 가능. */
    FORCE,
    /** 최신은 아니지만 지원 범위. 안내 모달을 한 번 표시(같은 최신 버전에는 다시 묻지 않음). */
    RECOMMEND,
    /** 아무것도 하지 않음. */
    OK
}
