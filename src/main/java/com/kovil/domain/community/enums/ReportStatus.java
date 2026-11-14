package com.kovil.domain.community.enums;

// 처리 후 불변 — RESOLVED(블라인드 집행) | DISMISSED(기각)는 되돌리지 않는다.
public enum ReportStatus {
    PENDING,
    RESOLVED,
    DISMISSED
}
