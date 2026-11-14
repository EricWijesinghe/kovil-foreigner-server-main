package com.kovil.common.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class BusinessNumberGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int RAND_LENGTH = 6;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd");

    public String generate(BusinessNumberPrefix prefix) {
        return generate(prefix, LocalDate.now());
    }

    public String generate(BusinessNumberPrefix prefix, LocalDate date) {
        StringBuilder rand = new StringBuilder(RAND_LENGTH);
        for (int i = 0; i < RAND_LENGTH; i++) {
            rand.append(ALPHABET[SECURE_RANDOM.nextInt(ALPHABET.length)]);
        }
        return String.format("%s-%s-%s", prefix.getPrefix(), date.format(DATE_FMT), rand);
    }
}
