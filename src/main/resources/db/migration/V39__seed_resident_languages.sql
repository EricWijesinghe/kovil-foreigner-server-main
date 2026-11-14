-- =============================================================================
-- V39 — 언어 마스터 확장: 국내 체류 외국인 주요 언어 14개 (2026-11-13)
--   대상: 태국어 · 우즈베크어 · 러시아어 · 몽골어 · 크메르어 · 인도네시아어 · 필리핀어 · 미얀마어 ·
--         싱할라어 · 벵골어 · 힌디어 · 우르두어 · 카자흐어 · 키르기스어.
--   코드는 Locale.getLanguage() 가 돌려주는 1차 하위 태그다(서버가 Accept-Language 를 그렇게 자른다).
--   native_name 은 언어 선택 화면이 그 언어로 그리는 표기(core/kovil-l10n/data/locales.toml 의 native 와 같다).
--   번들: errors_* · notifications_* 는 kovil-l10n 프롬프트로 초안 → 린트 → 원어민 관점 검수 1회(사람 검수 대기).
--         번역이 빠진 키는 en 으로 폴백한다. 국가명은 dml/07(CLDR) 이 채운다 — 이 행들이 먼저 있어야 FK 가 선다.
--   V7 과 같이 멱등: 관리자 화면으로 먼저 추가된 행이 있어도 정본 표기로 갱신한다.
-- =============================================================================
INSERT INTO languages (code, native_name, english_name, is_default, active, sort_order)
VALUES ('th', 'ภาษาไทย', 'Thai', 0, 1, 7),
       ('uz', 'Oʻzbekcha', 'Uzbek', 0, 1, 8),
       ('ru', 'Русский', 'Russian', 0, 1, 9),
       ('mn', 'Монгол', 'Mongolian', 0, 1, 10),
       ('km', 'ភាសាខ្មែរ', 'Khmer', 0, 1, 11),
       ('id', 'Bahasa Indonesia', 'Indonesian', 0, 1, 12),
       ('fil', 'Filipino', 'Filipino', 0, 1, 13),
       ('my', 'မြန်မာ', 'Burmese', 0, 1, 14),
       ('si', 'සිංහල', 'Sinhala', 0, 1, 15),
       ('bn', 'বাংলা', 'Bengali', 0, 1, 16),
       ('hi', 'हिन्दी', 'Hindi', 0, 1, 17),
       ('ur', 'اردو', 'Urdu', 0, 1, 18),
       ('kk', 'Қазақша', 'Kazakh', 0, 1, 19),
       ('ky', 'Кыргызча', 'Kyrgyz', 0, 1, 20)
ON DUPLICATE KEY UPDATE native_name  = VALUES(native_name),
                        english_name = VALUES(english_name),
                        active       = VALUES(active),
                        sort_order   = VALUES(sort_order);
