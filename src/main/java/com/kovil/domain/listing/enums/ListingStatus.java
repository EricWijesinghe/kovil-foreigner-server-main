package com.kovil.domain.listing.enums;

import java.util.EnumSet;
import java.util.Set;

// 매물의 일생. **행을 지우지 않고 상태로 관리한다** — 광고는 허위매물 분쟁의 증거라
// 사라지면 "그때 뭐라고 적혀 있었나"를 답할 수 없다.
//
// ★ HIDDEN 과 BLOCKED 를 가른 이유: HIDDEN 은 중개사가 스스로 내린 것이라 되올릴 수 있지만,
//   BLOCKED 는 **관리자가 막은 것**이라 중개사가 풀면 차단의 의미가 없다.
public enum ListingStatus {

    DRAFT,
    ACTIVE,
    RESERVED,
    COMPLETED,
    EXPIRED,
    HIDDEN,
    BLOCKED,

    // 만료 후 연장 창(7일)이 닫혀 자동 종료된 것. **되살릴 수 없다.**
    //
    // ★ EXPIRED 와 가른 이유: EXPIRED 는 아직 연장할 수 있는 상태다. 하나로 합치면
    //   중개사 목록에서 "급히 연장해야 할 건"과 "이미 끝난 건"이 섞인다.
    // ★ COMPLETED 로 보내지 않는 이유: 그건 '거래 완료'라 성약률 통계가 오염된다.
    ARCHIVED;

    // 유학생에게 보이는 상태. 예약 중인 방도 보여 준다 — 조건이 맞으면 대기하려는 사람이 있다.
    private static final Set<ListingStatus> VISIBLE = EnumSet.of(ACTIVE, RESERVED);

    // ★ 한 번이라도 게시된 적이 있으면 **하드 삭제를 허용하지 않는다.**
    //   DRAFT 는 광고된 적이 없으므로 증거가 아니다 — 그것만 지울 수 있다.
    public boolean isDeletable() {
        return this == DRAFT;
    }

    public boolean isVisibleToPublic() {
        return VISIBLE.contains(this);
    }

    /*
     * 중개사가 스스로 되올릴 수 있는가.
     *
     * ★ BLOCKED 는 관리자만 푼다 — 허위매물로 막은 것을 올린 사람이 풀면 차단이 무의미하다.
     * ★ ARCHIVED 는 아무도 못 푼다 — 연장 창이 닫힌 것이라 되살리려면 새로 등록해야 한다.
     * ★ EXPIRED 는 **상태만으로는 부족하다.** 연장 7일 창이 남았는지는 expires_at 을 봐야 하고,
     *   그 판정은 정책(배치·서비스)의 몫이다. 여기서는 "되살릴 여지가 있는 상태"까지만 말한다.
     */
    public boolean isBrokerRecoverable() {
        return this == HIDDEN || this == EXPIRED;
    }
}
