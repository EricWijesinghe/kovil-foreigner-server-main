package com.kovil.infra.crypto.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CryptoErrorCode implements ErrorCode {
    HMAC_HASHING_FAILED(
            "HMAC_HASHING_FAILED",
            "정보 암호화 과정에서 오류가 발생했습니다.",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    AES_ENCRYPT_FAILED(
            "AES_ENCRYPT_FAILED",
            "정보 암호화 하는 과정에서 오류가 발생했습니다.",
            HttpStatus.INTERNAL_SERVER_ERROR
    ),
    AES_DECRYPT_FAILED(
            "AES_DECRYPT_FAILED",
            "정보 복호화 하는 과정에서 오류가 발생했습니다.",
            HttpStatus.INTERNAL_SERVER_ERROR
    );

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
