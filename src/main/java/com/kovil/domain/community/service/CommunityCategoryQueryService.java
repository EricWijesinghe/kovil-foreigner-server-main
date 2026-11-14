package com.kovil.domain.community.service;

import com.kovil.domain.community.dto.response.CommunityCategoryResponse;
import com.kovil.domain.community.entity.CommunityCategory;
import com.kovil.domain.community.entity.CommunityCategoryTranslation;
import com.kovil.domain.community.repository.CommunityCategoryRepository;
import com.kovil.domain.community.repository.CommunityCategoryTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 이름 폴백: 요청 언어 → en → code. 번역이 없다고 게시판을 감추면 글을 아예 못 쓴다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityCategoryQueryService {

    private final CommunityCategoryRepository categoryRepository;
    private final CommunityCategoryTranslationRepository translationRepository;

    public List<CommunityCategoryResponse> findActive() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        Set<String> languages = new LinkedHashSet<>();
        if (language != null && !language.isBlank()) {
            languages.add(language);
        }
        languages.add("en");

        Map<String, Map<Long, String>> names = translationRepository.findByLanguageCodeIn(languages).stream()
                .collect(Collectors.groupingBy(CommunityCategoryTranslation::getLanguageCode,
                        Collectors.toMap(CommunityCategoryTranslation::getCategoryId,
                                CommunityCategoryTranslation::getName)));

        return categoryRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(category -> new CommunityCategoryResponse(
                        category.getUuid(), category.getCode(),
                        resolveName(names, language, category), category.isAnonymous()))
                .toList();
    }

    private String resolveName(Map<String, Map<Long, String>> names, String language, CommunityCategory category) {
        String requested = names.getOrDefault(language, Map.of()).get(category.getId());
        if (requested != null) {
            return requested;
        }
        String english = names.getOrDefault("en", Map.of()).get(category.getId());
        return english != null ? english : category.getCode();
    }
}
