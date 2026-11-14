-- Apple 로그인으로 받은 refresh_token 을 암호문으로 둔다. 탈퇴할 때 Apple 에 철회(/auth/revoke)를 보내려면 이 값이 있어야 한다.
-- 철회는 그 토큰을 낸 client_id(앱 번들 ID 또는 웹 Services ID)로 보내야 해서 짝으로 둔다.
-- Apple 만 값이 있고 나머지 수단은 NULL 이다. 탈퇴로 수단을 해제할 때, Apple 이 연결 철회를 알려 올 때 지운다.
-- 끝에 NULL 칸을 붙이는 변경이라 MySQL 8 은 INSTANT 로 처리한다.
ALTER TABLE user_auth
    ADD COLUMN provider_refresh_token VARCHAR(512) NULL COMMENT 'Apple refresh_token 암호문(AES-GCM). 탈퇴 철회용. 해제하면 NULL',
    ADD COLUMN provider_client_id VARCHAR(100) NULL COMMENT 'provider_refresh_token 을 낸 Apple client_id(번들 ID · Services ID). 철회에 같은 값을 쓴다';
