package com.kovil.domain.verification.entity;

import com.kovil.common.entity.BaseEntity;
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
@Table(name = "resident_status_documents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResidentStatusDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "resident_status_id", nullable = false)
    private Long residentStatusId;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    // 0 = 선택 제출(있으면 심사가 빨라진다). 필수 누락만 제출을 막는다(FR-204).
    @Builder.Default
    @Column(name = "is_required", nullable = false)
    private Boolean isRequired = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;


    public void update(Boolean isRequired) {
        this.isRequired = isRequired;
    }
}
