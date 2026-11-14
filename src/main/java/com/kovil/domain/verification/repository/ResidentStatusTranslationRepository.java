package com.kovil.domain.verification.repository;

import com.kovil.domain.verification.entity.ResidentStatusTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ResidentStatusTranslationRepository
        extends JpaRepository<ResidentStatusTranslation, Long> {

    // 요청 언어와 폴백 언어를 한 번에 가져온다 — 언어마다 조회하면 왕복이 늘어난다.
    List<ResidentStatusTranslation> findByLanguageCodeIn(Collection<String> languageCodes);

    List<ResidentStatusTranslation> findByResidentStatusId(Long residentStatusId);

    List<ResidentStatusTranslation> findByResidentStatusIdIn(Collection<Long> ids);
}
