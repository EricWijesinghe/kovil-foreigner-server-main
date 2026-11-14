package com.kovil.infra.push;

import java.util.Map;

public record PushMessage(String token, String title, String body, Map<String, String> data) {

    public static PushMessage of(String token, String title, String body, Map<String, String> data) {
        return new PushMessage(token, title, body, data == null ? Map.of() : Map.copyOf(data));
    }
}
