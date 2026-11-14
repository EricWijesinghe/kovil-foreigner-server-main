package com.kovil.infra.storage;

// confirm 검증의 근거는 클라이언트 신고값이 아니라 이 실측값이다.
public record StoredObjectMetadata(String contentType, Long contentLength) {
}
