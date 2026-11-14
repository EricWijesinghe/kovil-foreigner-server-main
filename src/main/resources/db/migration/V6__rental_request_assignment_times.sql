-- 임대 요청의 배정·상담 시작 시각.
--
-- 관리자 화면의 "담당 08-11 배정"·진행 타임라인("상담중 08-11 11:02")이 필요로 하는 값인데
-- rental_requests에는 created_at·updated_at·closed_at뿐이라 그릴 수가 없었다.
--
-- 상태 전이 이력 테이블(rental_request_events)을 만들지 않은 이유: 지금 화면이 요구하는 것은
-- 이 두 시점뿐이고, 이력 테이블은 대시보드 지표라는 **아직 없는 요구**를 전제로 한 설계다.
-- 나중에 처리 시간 지표가 필요해지면 그때 이력 테이블을 얹고 이 두 컬럼은 그대로 두면 된다.
--
-- 기존 행은 NULL로 남는다 — 언제 배정됐는지 서버가 모르기 때문이다.
-- created_at으로 채우면 "접수 시각 = 배정 시각"이라는 **거짓**이 데이터에 박힌다.
ALTER TABLE rental_requests
    ADD COLUMN assigned_at DATETIME(6) NULL COMMENT '담당자가 붙은 시각. NULL = 미배정이거나 V6 이전 건' AFTER assigned_manager_id,
    ADD COLUMN started_at  DATETIME(6) NULL COMMENT '상담 시작(SUBMITTED→IN_PROGRESS) 시각. NULL = 시작 전이거나 V6 이전 건' AFTER assigned_at;
