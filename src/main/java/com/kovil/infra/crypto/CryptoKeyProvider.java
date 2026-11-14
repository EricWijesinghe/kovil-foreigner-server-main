package com.kovil.infra.crypto;

// 키는 버전을 갖는다. 유출됐을 때 갈아탈 수 있어야 하기 때문이다 —
// 암호문에 버전이 적혀 있지 않으면 옛 키와 새 키가 공존할 수 없고,
// 교체는 "앱을 세우고 전부 변환"이라는 무중단 불가능한 작업이 된다.
public interface CryptoKeyProvider {

    int activeVersion();

    byte[] aesKey(int version);

    byte[] hmacKey(int version);

    // 쓰기는 언제나 현재 버전으로 한다. 읽기만 옛 버전을 허용한다.
    default byte[] getAesKey() {
        return aesKey(activeVersion());
    }

    default byte[] getHmacKey() {
        return hmacKey(activeVersion());
    }
}
