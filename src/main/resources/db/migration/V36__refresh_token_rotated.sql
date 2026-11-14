-- 회전에 쓰인 리프레시 토큰을 따로 표시한다 — 재사용(탈취) 판정은 이 상태만 본다.
-- REVOKED 는 이제 정상 종료(로그아웃 · 세션 상한 정리 · 전 세션 끊기)만 뜻한다. 그런 기기가 나중에 리프레시해도
-- 다른 세션을 끊지 않는다(전에는 같은 REVOKED 라 새로 로그인한 기기까지 끊었다).
-- ENUM 끝에 값을 붙이는 변경이라 MySQL 8 은 INSTANT 로 처리한다.
ALTER TABLE refresh_tokens
    MODIFY COLUMN status ENUM ('ACTIVE','REVOKED','EXPIRED','ROTATED') NOT NULL DEFAULT 'ACTIVE';

-- 배포 전에 회전된 토큰도 재사용 탐지를 잃지 않게 — 자식(parent_token_id)이 있는 REVOKED 행은 회전으로 폐기된 것이다
-- (자식을 만드는 길은 회전 하나뿐이다).
UPDATE refresh_tokens p
    JOIN refresh_tokens c ON c.parent_token_id = p.id
SET p.status = 'ROTATED'
WHERE p.status = 'REVOKED';
