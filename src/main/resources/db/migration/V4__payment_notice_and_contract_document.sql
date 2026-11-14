-- ① 월세 납부 통지(CHECKING): 유저의 "보냈어요" 주장. 수납(사실)이 아니라 통지(주장)라
--    rent_payments 행을 만들지 않고 회차에 주장 필드로 남는다. 확정은 여전히 담당자 O/X다.
--    재통지(revert 후)는 덮어쓴다 — 주장 이력이 필요해지면 그때 테이블로 뺀다.
ALTER TABLE rent_billings
    ADD COLUMN notified_at             DATETIME(6)  NULL COMMENT '납부 통지 시각. CHECKING 진입점 — NULL이면 통지 이력 없음' AFTER memo,
    ADD COLUMN notified_amount         BIGINT       NULL COMMENT '유저가 주장하는 이체액(대조 재료, 검증 안 된 값)' AFTER notified_at,
    ADD COLUMN notified_paid_at        DATETIME(6)  NULL COMMENT '유저가 주장하는 이체 시각' AFTER notified_amount,
    ADD COLUMN notified_depositor_name VARCHAR(100) NULL COMMENT '입금자명 — 계약자명과 다르게 이체했을 때 대조가 막히는 것을 막는다' AFTER notified_paid_at,
    ADD COLUMN notified_memo           VARCHAR(200) NULL COMMENT '통지 메모("8월분")' AFTER notified_depositor_name;

-- ② 계약서 원본 문서 (PO 번복 2026-08-13 — "계약서 미저장" 확정을 명시적으로 뒤집었다):
--    담당자가 올린 스캔(PDF/이미지)을 유저 앱이 열람한다. 민감 파일 — 공개 URL 없음(presigned 전용).
--    교체는 행 누적으로 — 최신 행이 현재 문서고, 왜 문서가 바뀌었는지가 이력으로 남는다.
CREATE TABLE contract_documents
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    uuid        CHAR(36)    NOT NULL,
    contract_id BIGINT      NOT NULL,
    file_id     BIGINT      NOT NULL COMMENT 'files.purpose = CONTRACT_DOC. 공개 URL 없음',
    uploaded_by BIGINT      NOT NULL COMMENT '업로드 담당자',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_cd_uuid (uuid),
    UNIQUE KEY uq_cd_file (file_id) COMMENT '한 파일 = 한 문서(교체는 재업로드)',
    INDEX idx_cd_contract (contract_id, id),
    CONSTRAINT fk_cd_contract FOREIGN KEY (contract_id) REFERENCES contracts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cd_file FOREIGN KEY (file_id) REFERENCES files (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cd_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '계약서 원본 스캔. 최신 행이 현재 문서 — 교체 이력은 행 누적';
