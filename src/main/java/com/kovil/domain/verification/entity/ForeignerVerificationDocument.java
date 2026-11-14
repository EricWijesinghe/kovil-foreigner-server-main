package com.kovil.domain.verification.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@SuperBuilder
@Table(name = "foreigner_verification_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForeignerVerificationDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "verification_id", nullable = false)
    private Long verificationId;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    // 스냅샷 — 마스터가 바뀌어도 "무엇을 냈는지"는 고정된다.
    @Column(name = "document_type_code", nullable = false, length = 40)
    private String documentTypeCode;

    // uq_fvd_file — 한 파일은 한 제출물에만 붙는다. 재제출은 재업로드다(감사 무결성).
    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
