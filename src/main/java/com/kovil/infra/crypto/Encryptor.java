package com.kovil.infra.crypto;

public interface Encryptor {
    String encrypt(String plainText);

    String decrypt(String cipherText);
}
