package com.kovil.domain.listing.dto.response;

import com.kovil.domain.listing.entity.ListingDescriptionTranslation;

public record ListingTranslationResponse(
        String languageCode,
        String content,
        String engine,
        boolean cached,
        boolean translated) {

    public static ListingTranslationResponse of(ListingDescriptionTranslation t, boolean cached) {
        return new ListingTranslationResponse(
                t.getLanguageCode(), t.getContent(), t.getEngine(), cached, true);
    }

    public static ListingTranslationResponse original(String languageCode, String korean) {
        return new ListingTranslationResponse(languageCode, korean, null, false, false);
    }
}
