-- =============================================================================
-- V7 — 언어 마스터 확장: vi·zh·ja·ne (PO 지시 2026-08-21)
--   운영 DB에는 관리자 마스터 화면으로 이미 추가된 행(vi 등)이 있을 수 있다 —
--   uq_languages_code 충돌 시 갱신으로 눕혀 멱등하게 만든다(이 마이그레이션이 정본 표기를 쓴다).
--   앱 번들: vi 실번역(원어민 검수 대기) · zh/ja/ne 기계 번역 초안 — 폴백은 여전히 en.
-- =============================================================================
INSERT INTO languages (code, native_name, english_name, is_default, active, sort_order)
VALUES ('vi', 'Tiếng Việt', 'Vietnamese', 0, 1, 3),
       ('zh', '中文', 'Chinese', 0, 1, 4),
       ('ja', '日本語', 'Japanese', 0, 1, 5),
       ('ne', 'नेपाली', 'Nepali', 0, 1, 6)
ON DUPLICATE KEY UPDATE native_name  = VALUES(native_name),
                        english_name = VALUES(english_name),
                        active       = VALUES(active),
                        sort_order   = VALUES(sort_order);
