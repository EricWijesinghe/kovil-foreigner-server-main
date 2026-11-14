package com.kovil.infra.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 크리덴셜 없이 부팅·테스트가 가능해야 하므로 기본값이다.
// headObject가 항상 통과하도록 고정 메타데이터를 돌려준다 — confirm 흐름을 끝까지 태우기 위해서다.
@Slf4j
@Component
@ConditionalOnProperty(name = "storage.provider", havingValue = "fake", matchIfMissing = true)
public class FakeStorageClient implements StorageClient {

    private static final String FAKE_HOST = "https://storage.invalid/";

    // 이동이 실제로 일어났는지 테스트가 볼 수 있게 남긴다.
    private final Map<String, String> moved = new ConcurrentHashMap<>();

    @Override
    public PresignedUpload presignPut(String key, String contentType, Duration expiresIn) {
        return new PresignedUpload(key, FAKE_HOST + key + "?upload", LocalDateTime.now().plus(expiresIn));
    }

    @Override
    public String presignGet(String key, Duration expiresIn) {
        return FAKE_HOST + key + "?read&ttl=" + expiresIn.toSeconds();
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String key) {
        return Optional.of(new StoredObjectMetadata("image/png", 1024L));
    }

    @Override
    public void moveObject(String sourceKey, String destinationKey) {
        moved.put(sourceKey, destinationKey);
        log.info("[FakeStorage] move {} -> {}", sourceKey, destinationKey);
    }

    @Override
    public String publicUrl(String key) {
        return FAKE_HOST + key;
    }

    public Map<String, String> movedObjects() {
        return Map.copyOf(moved);
    }
}
