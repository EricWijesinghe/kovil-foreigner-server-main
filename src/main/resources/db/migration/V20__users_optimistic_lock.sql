-- 회원 행에 낙관적 락 컬럼을 넣는다.
--
-- ★ 왜 필요한가 — JPA 는 @DynamicUpdate 가 없으면 UPDATE 에 **전 컬럼**을 싣는다.
--   관리자가 회원을 읽은 뒤 커밋하기 전에 그 회원이 탈퇴하면, 관리자 트랜잭션이 자기가 읽은
--   낡은 스냅샷으로 전부 덮어써 deleted_at 이 풀리고 **탈퇴가 NULL 로 지운 이름·전화·이메일이
--   되살아난다.** 지운 개인정보가 돌아오는 사고다(적대적 리뷰 2026-09-02).
--
-- version 이 있으면 뒤늦은 커밋이 ObjectOptimisticLockingFailureException 으로 드러나고
-- GlobalExceptionHandler 가 이미 409 CONFLICT 로 번역한다.
--
-- 기존 행은 0 에서 시작한다. DEFAULT 가 있어 애플리케이션 배포 전에 적용해도 안전하다.
ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '낙관적 락. 겹친 갱신으로 탈퇴가 되살아나는 것을 막는다';
