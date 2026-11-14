package com.kovil.domain.community.enums;

// 인기 후보의 시간창. 창 밖의 옛 글은 후보에서 빠진다 — 감쇠 점수와 별개의 1차 컷.
public enum PopularPeriod {
    DAY(1),
    WEEK(7),
    MONTH(30);

    private final int days;

    PopularPeriod(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
