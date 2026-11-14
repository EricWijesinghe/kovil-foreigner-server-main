package com.kovil.domain.language.repository;

import com.kovil.domain.language.entity.Language;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LanguageRepository extends JpaRepository<Language, Long> {

    List<Language> findByActiveTrueOrderBySortOrderAscCodeAsc();

    Optional<Language> findByCodeAndActiveTrue(String code);
}
