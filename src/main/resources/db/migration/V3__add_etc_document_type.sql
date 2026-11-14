-- 기타 증빙(ETC): 특정 신분의 요건 매트릭스에 속하지 않는, 전 신분 공통의 선택 제출 슬롯.
-- 요건 조회가 이 행을 찾아 항상 마지막에 선택(required=false)으로 붙인다.
-- 이 행을 비활성화하면 제출 화면에서 기타 슬롯 자체가 사라진다.
INSERT INTO document_types (code, is_sensitive, max_files, sort_order, active)
VALUES ('ETC', 1, 5, 999, 1);

INSERT INTO document_type_translations (document_type_id, language_code, name, guide_note)
SELECT d.id, t.lang, t.name, t.guide
FROM document_types d
         JOIN (SELECT 'en' lang, 'Other supporting documents' name,
                      'Anything else that helps us verify you — scholarship letter, bank statement, dormitory contract, etc. Optional.' guide
               UNION ALL SELECT 'ko', '기타 증빙 서류',
                      '본인 확인에 도움이 되는 서류라면 무엇이든 좋습니다 — 장학 증명, 잔고 증명, 기숙사 계약서 등. 선택 제출입니다.') t
WHERE d.code = 'ETC';
