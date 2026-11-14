package com.kovil.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

// FR-603 — 저장된 것은 키와 치환 값뿐이라 **읽는 시점의 언어로 렌더한다.**
// 유저가 언어를 바꾸면 과거 알림도 그 언어로 다시 읽힌다.
@Component
@RequiredArgsConstructor
public class NotificationRenderer {

    private final MessageSource messageSource;
    private final JsonMapper jsonMapper;

    public Rendered render(String templateCode, String paramsJson, Locale locale) {
        Object[] args = toArgs(paramsJson);
        return new Rendered(
                messageSource.getMessage(templateCode + ".title", args, templateCode, locale),
                messageSource.getMessage(templateCode + ".body", args, "", locale));
    }

    public record Rendered(String title, String body) {
    }

    // params는 {"0":"...","1":"..."} 형태의 위치 인자다.
    // Map을 쓰는 이유는 JSON 컬럼에 그대로 담기 위해서이고, 키 순서를 보장하려 TreeMap으로 정렬한다.
    private Object[] toArgs(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new Object[0];
        }
        try {
            Map<String, Object> raw = jsonMapper.readValue(paramsJson, new TypeReference<>() {
            });
            return new TreeMap<>(raw).values().toArray();
        } catch (RuntimeException e) {
            // 치환 값이 깨져도 제목은 나가야 한다 — 알림이 통째로 사라지는 것보다 낫다.
            return new Object[0];
        }
    }

    public List<String> keysOf(String templateCode) {
        return List.of(templateCode + ".title", templateCode + ".body");
    }
}
