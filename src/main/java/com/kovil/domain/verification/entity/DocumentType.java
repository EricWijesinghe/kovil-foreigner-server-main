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
@Table(name = "document_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentType extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    // 신분증류 여부. 보관기간·접근로그 정책의 축이며, FE는 사용자에게 주의 문구를 띄우는 데 쓴다.
    @Builder.Default
    @Column(name = "is_sensitive", nullable = false)
    private Boolean isSensitive = true;

    // 여권 앞·뒤처럼 복수장이 필요한 문서의 상한.
    @Builder.Default
    @Column(name = "max_files", nullable = false)
    private Integer maxFiles = 1;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;


    public void update(Boolean isSensitive, Integer maxFiles, Integer sortOrder, Boolean active) {
        this.isSensitive = isSensitive;
        this.maxFiles = maxFiles;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    // /order 재정렬 전용 — 이름·활성은 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
