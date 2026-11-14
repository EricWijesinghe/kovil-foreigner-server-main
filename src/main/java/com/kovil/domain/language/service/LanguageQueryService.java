package com.kovil.domain.language.service;

import com.kovil.domain.language.dto.response.LanguageResponse;
import com.kovil.domain.language.repository.LanguageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LanguageQueryService {

    private final LanguageRepository languageRepository;

    public List<LanguageResponse> findActiveLanguages() {
        return languageRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()
                .stream()
                .map(LanguageResponse::from)
                .toList();
    }

    public boolean isSupported(String code) {
        return code != null && languageRepository.findByCodeAndActiveTrue(code).isPresent();
    }
}
