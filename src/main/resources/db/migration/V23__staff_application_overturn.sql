-- ★ 거절 번복 — 거절했던 신청을 다시 승인으로 되돌린다(PO 지시 2026-09-17).
--
-- 왜 새 컬럼인가: 기존 reviewed_by / reviewed_at / reject_reason 을 덮어쓰면
-- **원래 누가 왜 거절했는지가 사라진다.** 그러면 번복 자체를 감사할 수 없다 —
-- "처음부터 승인된 건"과 "거절했다가 뒤집은 건"이 구분되지 않는다.
-- 인증 무효화(foreigner_verifications.revoked_*)가 같은 이유로 같은 모양을 쓴다.
--
-- 읽는 법: reviewed_* 는 **거절 시점의 결정**이고, overturned_* 가 있으면 그 결정이
-- 뒤집힌 것이다. 둘 다 남으므로 행 하나로 "누가 거절했고 누가 뒤집었나"가 읽힌다.
ALTER TABLE staff_applications
    ADD COLUMN overturned_by BIGINT     NULL COMMENT '거절을 뒤집어 승인한 관리자(users.id)' AFTER reviewed_at,
    ADD COLUMN overturned_at DATETIME(6) NULL COMMENT '번복 시각. NULL 이면 번복된 적 없다' AFTER overturned_by,
    ADD CONSTRAINT fk_sa_overturner FOREIGN KEY (overturned_by) REFERENCES users (id) ON DELETE SET NULL;
