-- 월세 고지·수납 기능 폐기(PO 결정 2026-09-14).
-- 유저의 월세 카드, 담당자의 월세 대장·수납 확인, 자동 고지 알림이 모두 사라졌다.
-- 계약은 monthlyRent·paymentDay 를 **계약 조건**으로만 보관한다 — 청구·수납 추적은 하지 않는다.
--
-- 해지 승인의 "미납 CHECKING 확인" 게이트도 함께 사라졌다. 미납 회차라는 개념이 없어져
-- 근거를 잃었기 때문이다(PO 확인). 정산 확인이 다시 필요해지면 회차가 아닌 다른 축으로 세운다.

-- rent_payments 가 rent_billings 를 FK 로 잡고 있다 — 자식부터 지운다.
DROP TABLE IF EXISTS rent_payments;
DROP TABLE IF EXISTS rent_billings;
