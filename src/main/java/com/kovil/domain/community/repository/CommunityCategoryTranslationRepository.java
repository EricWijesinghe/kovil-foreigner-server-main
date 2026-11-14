package com.kovil.domain.community.repository;

import com.kovil.domain.community.entity.CommunityCategoryTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CommunityCategoryTranslationRepository extends JpaRepository<CommunityCategoryTranslation, Long> {

    List<CommunityCategoryTranslation> findByLanguageCodeIn(Collection<String> languageCodes);

    List<CommunityCategoryTranslation> findByCategoryId(Long categoryId);

    List<CommunityCategoryTranslation> findByCategoryIdIn(Collection<Long> ids);
}
