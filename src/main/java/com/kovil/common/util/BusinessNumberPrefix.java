package com.kovil.common.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum BusinessNumberPrefix {
    RENTAL_REQUEST("RQ"),
    LISTING("LS");

    private final String prefix;
}
