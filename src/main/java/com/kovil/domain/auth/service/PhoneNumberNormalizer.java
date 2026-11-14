package com.kovil.domain.auth.service;

import com.kovil.domain.auth.exception.PhoneVerificationException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

import static com.kovil.domain.auth.exception.AuthErrorCode.PHONE_NOT_KR;
import static com.kovil.domain.auth.exception.AuthErrorCode.PHONE_REQUIRED;

// 한국 휴대폰 번호만 받는다(FR-101). 번호는 저장·해시 전에 반드시 여기를 통과해
// 하나의 표기(01012345678)로 정규화된다 — 같은 번호가 표기 차이로 다른 phone_hash가 되면
// "한 번호 = 한 계정"(INV-01)이 깨진다.
@Component
public class PhoneNumberNormalizer {

    private static final Pattern KR_MOBILE = Pattern.compile("^01[016-9]\\d{7,8}$");

    public String normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            throw new PhoneVerificationException(PHONE_REQUIRED);
        }

        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.startsWith("82")) {
            digits = "0" + digits.substring(2);
        }

        if (!KR_MOBILE.matcher(digits).matches()) {
            throw new PhoneVerificationException(PHONE_NOT_KR);
        }
        return digits;
    }
}
