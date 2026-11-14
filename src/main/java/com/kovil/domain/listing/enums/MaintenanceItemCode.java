package com.kovil.domain.listing.enums;

// 법정 비목 8개. 다방은 같은 개념을 분기마다 다른 코드로 부르지만(F01… / PUBLIC_USE_RATES…)
// **우리는 한 벌만 쓴다** — 두 벌을 들고 갈 이유가 없다.
public enum MaintenanceItemCode {
    COMMON,
    ELECTRIC,
    WATER,
    GAS,
    HEATING,
    INTERNET,
    TV,
    ETC
}
