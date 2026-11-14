package com.kovil.infra.translation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/*
 * 키 없이 도는 기본 구현. `translation.provider` 를 주지 않으면 이쪽이 뜬다.
 *
 * ★★ **번역한 척하지 않는다.** 원문 앞에 표식을 붙여 돌려주므로, 화면을 보는 순간
 *    "이건 가짜다"를 알 수 있다. 그럴듯한 가짜를 돌려주면 키가 없는 채로 배포돼도
 *    아무도 모르고, 유학생이 한국어를 영어로 착각해 읽는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "translation.provider", havingValue = "fake", matchIfMissing = true)
public class FakeTranslationClient implements TranslationClient {

    @Override
    public String translate(String koreanText, String targetLanguageCode) {
        if (koreanText == null || koreanText.isBlank()) {
            return null;
        }
        log.debug("[translation] fake {} chars → {}", koreanText.length(), targetLanguageCode);
        return "[FAKE " + targetLanguageCode + "] " + koreanText;
    }

    @Override
    public String engineName() {
        return "fake";
    }
}
