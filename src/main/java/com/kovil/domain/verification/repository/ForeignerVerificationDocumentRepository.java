package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface ForeignerVerificationDocumentRepository
        extends JpaRepository<ForeignerVerificationDocument, Long> {

    List<ForeignerVerificationDocument> findByVerificationIdOrderBySortOrderAscIdAsc(Long verificationId);

    /** 탈퇴 파기용 — 재제출이 새 인증 행을 만들므로 그 사람의 **모든** 인증 건을 훑는다. */
    List<ForeignerVerificationDocument> findByVerificationIdIn(Collection<Long> verificationIds);

    // 재제출 선검사(AUDIT) — 이전 제출에 이미 묶인 파일인지. uq_fvd_file 500을 4xx로 바꾼다.
    boolean existsByFileId(Long fileId);
}
