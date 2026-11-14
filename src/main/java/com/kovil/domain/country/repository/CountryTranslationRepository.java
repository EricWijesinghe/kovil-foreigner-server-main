package com.kovil.domain.country.repository;

import com.kovil.domain.country.entity.CountryTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CountryTranslationRepository extends JpaRepository<CountryTranslation, Long> {

    List<CountryTranslation> findByLanguageCode(String languageCode);
}
