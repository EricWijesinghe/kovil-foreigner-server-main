-- =============================================================================
-- dml/06_file_domain_kovil.sql — 공개 파일 주소를 file.wonjoomin.com → file.kovil.co.kr 로(2026-11-02)
--
-- 공개 파일 주소는 올릴 때 통째로 저장된다(files.file_url · community_post_images.image_url ·
-- users.profile_image_url). wonjoomin.com 을 닫으면 옛 주소가 모두 깨져서 새 도메인으로 바꿔 쓴다.
-- 이미 바뀐 행은 LIKE 에 걸리지 않아 배포마다 다시 돌아도 그대로다.
--
-- ★ kovil 계정의 새 버킷으로 파일을 옮기고 file.kovil.co.kr 을 붙인 뒤, 서버 R2_* 도 같은 배포에서 바꾼다 —
--   그 전에 이 파일이 돌면 그 사이 사진이 안 뜬다. 파일 키는 그대로 복사돼 주소의 도메인만 바꾸면 된다.
-- ★ 글 사진 빼기 · 글 지우기 · 탈퇴가 이 주소로 files 원장을 찾아 버리므로 세 표를 같은 규칙으로 바꾼다.
-- =============================================================================

UPDATE files
SET file_url = REPLACE(file_url, 'https://file.wonjoomin.com/', 'https://file.kovil.co.kr/')
WHERE file_url LIKE 'https://file.wonjoomin.com/%';

UPDATE community_post_images
SET image_url = REPLACE(image_url, 'https://file.wonjoomin.com/', 'https://file.kovil.co.kr/')
WHERE image_url LIKE 'https://file.wonjoomin.com/%';

UPDATE users
SET profile_image_url = REPLACE(profile_image_url, 'https://file.wonjoomin.com/', 'https://file.kovil.co.kr/')
WHERE profile_image_url LIKE 'https://file.wonjoomin.com/%';
