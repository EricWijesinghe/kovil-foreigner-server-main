ALTER TABLE brokerage_office_change_requests
    MODIFY COLUMN status ENUM ('PENDING','APPROVED','REJECTED','CANCELED') NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING 동안 사무소 · 부문은 그대로다. APPROVED = 사무소에 적용됨, REJECTED = 사무소 그대로, CANCELED = 신청자가 거둬들임(사무소 그대로)';