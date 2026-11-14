package com.kovil.infra.storage;

import java.time.Duration;
import java.util.Optional;

// 벤더 중립 포트. 구현체는 infra/{provider}/storage에 두고 storage.provider로 교체한다(fake | r2).
// presignPut·presignGet은 로컬 서명 연산이지만 headObject·moveObject는 실제 API 호출이다 —
// 트랜잭션 안에서 부르면 외부 지연이 커넥션을 잡는다.
public interface StorageClient {

    PresignedUpload presignPut(String key, String contentType, Duration expiresIn);

    // 민감 파일의 유일한 열람 통로다(INV-08). 공개 URL을 만들지 않는 대신 매번 짧게 서명한다.
    String presignGet(String key, Duration expiresIn);

    Optional<StoredObjectMetadata> headObject(String key);

    void moveObject(String sourceKey, String destinationKey);

    String publicUrl(String key);
}
