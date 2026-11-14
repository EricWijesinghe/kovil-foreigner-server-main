package com.kovil.domain.user.enums;

import com.kovil.domain.user.exception.InvalidAuthTypeException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

import static com.kovil.domain.user.exception.UserErrorCode.AUTH_TYPE_NULL;
import static com.kovil.domain.user.exception.UserErrorCode.INVALID_AUTH_TYPE;

@Getter
@RequiredArgsConstructor
public enum AuthType {
    LOCAL("LOCAL"), APPLE("APPLE"), GOOGLE("GOOGLE"), FACEBOOK("FACEBOOK");

    private final String type;

    public static AuthType from(String value) {
        if (value == null) {
            throw new InvalidAuthTypeException(AUTH_TYPE_NULL);
        }
        return Arrays.stream(values())
                .filter(v -> v.getType().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new InvalidAuthTypeException(INVALID_AUTH_TYPE));
    }
}
