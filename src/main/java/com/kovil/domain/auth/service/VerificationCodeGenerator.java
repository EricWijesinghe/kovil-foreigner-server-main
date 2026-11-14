package com.kovil.domain.auth.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class VerificationCodeGenerator {

    private static final int CODE_LENGTH = 6;
    private static final int BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        return String.format("%0" + CODE_LENGTH + "d", random.nextInt(BOUND));
    }
}
