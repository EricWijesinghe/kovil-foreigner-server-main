package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.DocumentTypeTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DocumentTypeTranslationRepository
        extends JpaRepository<DocumentTypeTranslation, Long> {

    List<DocumentTypeTranslation> findByLanguageCodeIn(Collection<String> languageCodes);

    List<DocumentTypeTranslation> findByDocumentTypeId(Long documentTypeId);

    List<DocumentTypeTranslation> findByDocumentTypeIdIn(Collection<Long> ids);
}
