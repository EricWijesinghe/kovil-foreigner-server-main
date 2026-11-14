-- ★ 로그인 수단의 유니크를 **살아 있는 행에만** 건다.
--
-- 왜: 탈퇴는 하드 삭제를 못 한다(법적 보관 의무 + FK 연쇄 삭제 위험). 그래서 deleted_at 만 찍는데,
-- 유니크 키가 (auth_type, identifier) 뿐이면 **해제된 행이 슬롯을 영원히 붙잡아** 같은 신원으로
-- 재가입할 때 INSERT 가 막힌다. 그것을 피하려고 옛 행을 되살려 새 계정으로 옮기면 문제가 더 커진다 —
-- 옛 계정의 가입 경로가 사라지고, created_at 이 새 주인의 것으로 오독되고,
-- 무엇보다 동시 요청 둘이 같은 행을 UPDATE 해 한쪽 계정이 조용히 로그인 수단 0개가 된다
-- (같은 행이라 유니크 위반이 나지 않아 DB 가 잡지 못한다).
--
-- 활성 행에만 유니크를 걸면 그 전부가 사라진다: 재가입은 **그냥 새 행을 넣고**, 옛 행은 옛 계정에
-- 그대로 남아 이력이 되며, 동시 요청은 유니크 위반으로 한쪽이 실패해 드러난다.
-- blocked_identities.active_guard 와 같은 기법이다.
ALTER TABLE user_auth
    ADD COLUMN active_identifier VARCHAR(300) GENERATED ALWAYS AS
        (IF(deleted_at IS NULL, CONCAT(auth_type, ':', identifier), NULL)) STORED
        COMMENT '해제되지 않은 동안만 값을 갖는다. 해제되면 NULL 이 되어 슬롯이 풀린다';

-- 옛 유니크를 먼저 떼고 활성 유니크를 건다. 조회 축((auth_type, identifier))은 유니크가
-- 사라지므로 일반 인덱스로 남겨야 한다 — findByAuthTypeAndIdentifierAndDeletedAtIsNull 이 쓴다.
ALTER TABLE user_auth
    DROP INDEX uq_user_auth_type_identifier,
    ADD UNIQUE KEY uq_user_auth_active (active_identifier),
    ADD INDEX idx_user_auth_type_identifier (auth_type, identifier)
        COMMENT '로그인 조회 + 이력 조회(해제된 행 포함)';
