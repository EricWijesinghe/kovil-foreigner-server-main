package com.kovil.domain.listing.service;

import com.kovil.domain.language.repository.LanguageRepository;
import com.kovil.domain.listing.dto.response.ListingTranslationResponse;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.entity.ListingDescriptionTranslation;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.repository.ListingDescriptionTranslationRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.infra.translation.TranslationClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static com.kovil.domain.listing.exception.ListingErrorCode.LANGUAGE_NOT_SUPPORTED;
import static com.kovil.domain.listing.exception.ListingErrorCode.LISTING_NOT_FOUND;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingTranslationService {

    private static final List<ListingStatus> VISIBLE =
            List.of(ListingStatus.ACTIVE, ListingStatus.RESERVED);

    private static final String SOURCE_LANGUAGE = "ko";

    private static final String FAKE_ENGINE = "fake";

    private final ListingRepository listingRepository;
    private final ListingDescriptionTranslationRepository translationRepository;
    private final LanguageRepository languageRepository;
    private final TranslationClient translationClient;

    public ListingTranslationResponse translate(String listingUuid, String languageCode) {
        String target = normalize(languageCode);

        Listing listing = listingRepository.findPublicByUuid(listingUuid, VISIBLE)
                .orElseThrow(() -> new ListingException(LISTING_NOT_FOUND));

        String korean = listing.getDescription();
        if (korean == null || korean.isBlank()) {
            return ListingTranslationResponse.original(target, korean);
        }
        if (SOURCE_LANGUAGE.equals(target)) {
            return ListingTranslationResponse.original(target, korean);
        }

        int version = listing.getDescriptionVersion();

        Optional<ListingDescriptionTranslation> cached =
                translationRepository.findByListingIdAndLanguageCodeAndSourceVersion(
                        listing.getId(), target, version);
        if (cached.isPresent()) {
            if (isUsable(cached.get())) {
                return ListingTranslationResponse.of(cached.get(), true);
            }
            translationRepository.deleteOne(listing.getId(), target, version);
        }

        String translated = translationClient.translate(korean, target);
        if (translated == null || translated.isBlank()) {
            return ListingTranslationResponse.original(target, korean);
        }

        ListingDescriptionTranslation saved = save(listing.getId(), target, version, translated);

        if (saved == null) {
            return new ListingTranslationResponse(
                    target, translated, translationClient.engineName(), false, true);
        }
        return ListingTranslationResponse.of(saved, false);
    }

    private boolean isUsable(ListingDescriptionTranslation row) {
        if (FAKE_ENGINE.equals(row.getEngine())) {
            return FAKE_ENGINE.equals(translationClient.engineName());
        }
        return true;
    }

    private String normalize(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            throw new ListingException(LANGUAGE_NOT_SUPPORTED);
        }
        String trimmed = languageCode.trim().toLowerCase();
        languageRepository.findByCodeAndActiveTrue(trimmed)
                .orElseThrow(() -> new ListingException(LANGUAGE_NOT_SUPPORTED));
        return trimmed;
    }

    private ListingDescriptionTranslation save(Long listingId, String languageCode,
                                               int version, String content) {
        ListingDescriptionTranslation row = ListingDescriptionTranslation.builder()
                .listingId(listingId)
                .languageCode(languageCode)
                .sourceVersion(version)
                .content(content)
                .engine(translationClient.engineName())
                .createdAt(LocalDateTime.now())
                .build();
        try {
            return translationRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            ListingDescriptionTranslation winner = translationRepository
                    .findByListingIdAndLanguageCodeAndSourceVersion(listingId, languageCode, version)
                    .orElse(null);
            if (winner == null) {
                log.warn("[translation] 캐시 저장 실패 — 번역문은 그대로 내려보낸다 listing={} lang={}",
                        listingId, languageCode, e);
            }
            return winner;
        }
    }
}
