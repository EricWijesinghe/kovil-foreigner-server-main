package com.kovil.infra.translation;

public interface TranslationClient {

    String translate(String koreanText, String targetLanguageCode);

    String engineName();
}
