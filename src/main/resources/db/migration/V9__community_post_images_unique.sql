-- 이미지 교체는 delete→insert인데 (post_id, sort_order)에 DB 방어선이 없어, 동시 수정 경합이
-- 중복 행을 남기면 그 글이 포함된 모든 목록 조회가 지속 500이 된다(toMap Duplicate key).
-- 유니크가 근본 방어다 — 경합의 패자는 중복 데이터 대신 제약 위반으로 실패한다.

-- 기존 중복 정리(있다면 최신 행을 남긴다 — 마지막 수정이 사용자가 의도한 세트다)
DELETE t1 FROM community_post_images t1
    JOIN community_post_images t2
        ON t1.post_id = t2.post_id AND t1.sort_order = t2.sort_order AND t1.id < t2.id;

ALTER TABLE community_post_images
    ADD UNIQUE KEY uq_cpi_post_sort (post_id, sort_order);
