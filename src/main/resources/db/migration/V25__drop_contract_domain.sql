-- 계약 도메인 폐기(PO 결정 2026-10-06).
-- 제품이 "외국인 유학생을 위한 앱, 첫 영역은 부동산 임대"로 바뀌어 우리는 더 이상 전대차 계약의
-- 당사자가 아니다. 계약 · 계약서 원본 · 입주 상태 확인서 · 해지 · 수납 계좌가 모두 사라지고,
-- 수납 계좌에서만 쓰이던 은행 마스터도 함께 사라진다.
--
-- ⚠ 되돌릴 수 없다. 테이블과 행이 함께 사라지므로 운영에 적용하기 전에 반드시 RDS 수동
--   스냅샷을 떠 둔다(월세 테이블을 지운 V21 과 같은 절차).
--
-- 남기는 것:
--   · files 의 계약서 · 입주 사진 · 은행 아이콘 행과 R2 객체. 계약서는 민감 문서이고 보존 기간
--     판단이 남아 있어 지울지는 사람이 정한다. 서버는 그 용도를 "은퇴"로 두고 새 업로드와 열람만 막는다.
--   · rental_requests 의 CONTRACTED 행. 상태 재편은 임대 요청을 파트너스로 옮기는 작업에서 한다.
--
-- shedlock 은 정리할 것이 없다 — 스케줄러에 ShedLock 이 배선된 적이 없어 계약 배치의 잠금 행이 생긴 적이 없다.

-- ① 사라지는 알림 종류의 행을 먼저 지운다. noti_type 이 VARCHAR 라 서버가 상수를 지운 채로 이 행을
--    읽으면 매핑이 터진다 — 알림함 목록과 관리자 발송 실패 대기열이 통째로 500 이 된다.
--    push_notifications 가 notification_outbox 를 FK(RESTRICT)로 잡고 있어 발송 이력부터 지운다.
--    월세 알림 5종(RENT_*)도 함께 지운다. 월세를 지운 V21 은 상수만 없애고 행을 남겼다 — 그 행이 발송 큐에 있으면
--    워커가 큐를 읽는 쿼리 전체가 실패해 **어떤 알림도 나가지 않는다.**
DELETE FROM push_notifications
WHERE outbox_id IN (SELECT id
                    FROM notification_outbox
                    WHERE noti_type IN ('CONTRACT_ACTIVATED',
                                        'CONTRACT_TERMINATION_APPROVED',
                                        'CONTRACT_TERMINATION_REJECTED',
                                        'CONTRACT_TERMINATED',
                                        'CONTRACT_EXPIRING_SOON',
                                        'MOVE_IN_INSPECTION_REGISTERED',
                                        'RENT_DUE',
                                        'RENT_DUE_REMINDER',
                                        'RENT_PAID_CONFIRMED',
                                        'RENT_OVERDUE',
                                        'RENT_PAYMENT_REQUEST'));

DELETE FROM notification_outbox
WHERE noti_type IN ('CONTRACT_ACTIVATED',
                    'CONTRACT_TERMINATION_APPROVED',
                    'CONTRACT_TERMINATION_REJECTED',
                    'CONTRACT_TERMINATED',
                    'CONTRACT_EXPIRING_SOON',
                    'MOVE_IN_INSPECTION_REGISTERED',
                    'RENT_DUE',
                    'RENT_DUE_REMINDER',
                    'RENT_PAID_CONFIRMED',
                    'RENT_OVERDUE',
                    'RENT_PAYMENT_REQUEST');

DELETE FROM user_notifications
WHERE noti_type IN ('CONTRACT_ACTIVATED',
                    'CONTRACT_TERMINATION_APPROVED',
                    'CONTRACT_TERMINATION_REJECTED',
                    'CONTRACT_TERMINATED',
                    'CONTRACT_EXPIRING_SOON',
                    'MOVE_IN_INSPECTION_REGISTERED',
                    'RENT_DUE',
                    'RENT_DUE_REMINDER',
                    'RENT_PAID_CONFIRMED',
                    'RENT_OVERDUE',
                    'RENT_PAYMENT_REQUEST');

-- ② 자식부터 지운다. contracts 를 FK 로 잡는 것은 아래 셋(계약서 · 해지 · 확인서)뿐이다 —
--    월세 회차 두 테이블은 V21 에서 이미 사라졌다. 확인서는 사진 · 수정 이력이 다시 잡고 있다.
DROP TABLE IF EXISTS contract_documents;
DROP TABLE IF EXISTS contract_terminations;
DROP TABLE IF EXISTS move_in_inspection_photos;
DROP TABLE IF EXISTS move_in_inspection_revisions;
DROP TABLE IF EXISTS move_in_inspections;
DROP TABLE IF EXISTS contracts;

-- ③ 은행 마스터. 딸린 테이블이 없고, 계약은 은행을 FK 가 아니라 코드 스냅샷으로 들고 있었다.
DROP TABLE IF EXISTS banks;
