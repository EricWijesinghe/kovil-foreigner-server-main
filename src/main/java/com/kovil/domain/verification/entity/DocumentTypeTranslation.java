package com.kovil.domain.verification.entity;

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

@Getter
@Entity
@Builder
@Table(name = "document_type_translations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@lombok.AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DocumentTypeTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "document_type_id", nullable = false)
    private Long documentTypeId;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    // 촬영 가이드. 반려율을 좌우하는 문구라 제출 화면에서 반드시 보여줘야 한다.
    @Column(name = "guide_note", length = 500)
    private String guideNote;
}
