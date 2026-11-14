package com.kovil.domain.user.enums;

import com.kovil.domain.user.exception.InvalidUserStatusException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

import static com.kovil.domain.user.exception.UserErrorCode.INVALID_USER_STATUS;
import static com.kovil.domain.user.exception.UserErrorCode.USER_STATUS_NULL;

@Getter
@RequiredArgsConstructor
public enum UserStatus {
    ACTIVE("ACTIVE"), SUSPENDED("SUSPENDED"), WITHDRAWN("WITHDRAWN");

    private final String status;

    public static UserStatus from(String value) {
        if (value == null) {
            throw new InvalidUserStatusException(USER_STATUS_NULL);
        }
        return Arrays.stream(values())
                .filter(v -> v.getStatus().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new InvalidUserStatusException(INVALID_USER_STATUS));
    }
}
