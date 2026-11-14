package com.kovil.common.util;

public final class MaskingUtil {

    private MaskingUtil() {
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 7) {
            return "*".repeat(digits.length());
        }
        String prefix = digits.substring(0, 3);
        String suffix = digits.substring(digits.length() - 4);
        int maskedLen = digits.length() - 7;
        return prefix + "*".repeat(maskedLen) + suffix;
    }

    public static String maskAccount(String account) {
        if (account == null || account.isBlank()) {
            return account;
        }
        String digits = account.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "*".repeat(digits.length());
        }
        String suffix = digits.substring(digits.length() - 4);
        int maskedLen = digits.length() - 4;
        return "*".repeat(maskedLen) + suffix;
    }

    
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String head = local.substring(0, Math.min(2, local.length()));
        return head + "***" + email.substring(at);
    }

    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String trimmed = name.trim();
        int len = trimmed.length();
        if (len == 1) {
            return trimmed;
        }
        if (len == 2) {
            return trimmed.charAt(0) + "*";
        }
        String first = trimmed.substring(0, 1);
        String last = trimmed.substring(len - 1);
        return first + "*".repeat(len - 2) + last;
    }
}