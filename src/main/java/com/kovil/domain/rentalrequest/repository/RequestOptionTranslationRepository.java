package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RequestOptionTranslationRepository extends JpaRepository<RequestOptionTranslation, Long> {

    List<RequestOptionTranslation> findByLanguageCodeIn(Collection<String> languageCodes);

    List<RequestOptionTranslation> findByRequestOptionId(Long requestOptionId);

    List<RequestOptionTranslation> findByRequestOptionIdIn(Collection<Long> ids);
}
