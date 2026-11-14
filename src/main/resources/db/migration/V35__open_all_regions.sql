-- 전국 서비스 — 지금까지 서울만 열려 있던 시도를 한 번 연다. 시군구 · 전남광주(12)는 dml/03_regions.sql 이 열린 채로 넣는다.
-- (그 DML 은 배포마다 돌지만 이미 있는 행의 열림은 건드리지 않는다 — 관리자가 닫은 것을 되돌리지 않게. 그래서 처음 한 번은 여기서.)
-- 광주(29) · 전남(46)은 2026-07-01 전남광주통합특별시(12)로 합쳐져 열지 않는다 — dml/03 이 닫힌 채로 둔다.
UPDATE regions
SET is_active = 1
WHERE parent_id IS NULL
  AND is_active = 0
  AND code NOT IN ('29', '46');
