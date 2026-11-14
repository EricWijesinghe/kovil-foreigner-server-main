package com.kovil.infra.cloudflare.storage;

import com.kovil.infra.storage.PresignedUpload;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.StoredObjectMetadata;
import com.kovil.infra.storage.exception.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static com.kovil.infra.storage.exception.StorageErrorCode.STORAGE_UNAVAILABLE;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.provider", havingValue = "r2")
public class R2StorageClient implements StorageClient {

    private final R2Properties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    // Content-Type을 서명에 포함한다 — 다른 타입으로 올리면 스토리지가 거부한다.
    @Override
    public PresignedUpload presignPut(String key, String contentType, Duration expiresIn) {
        var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(contentType)
                        .build())
                .build());

        return new PresignedUpload(
                key,
                presigned.url().toString(),
                LocalDateTime.ofInstant(presigned.expiration(), ZoneId.systemDefault()));
    }

    @Override
    public String presignGet(String key, Duration expiresIn) {
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(expiresIn)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(key)
                                .build())
                        .build())
                .url().toString();
    }

    @Override
    public Optional<StoredObjectMetadata> headObject(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket()).key(key).build());
            return Optional.of(new StoredObjectMetadata(response.contentType(), response.contentLength()));
        } catch (NoSuchKeyException e) {
            // 업로드하지 않은 키다 — 장애가 아니라 정상 분기다.
            return Optional.empty();
        } catch (SdkException e) {
            log.error("[r2] headObject 실패 key={}", key, e);
            throw new StorageException(STORAGE_UNAVAILABLE);
        }
    }

    // R2에는 rename이 없다. copy 후 delete가 이동이다.
    @Override
    public void moveObject(String sourceKey, String destinationKey) {
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(properties.bucket()).sourceKey(sourceKey)
                    .destinationBucket(properties.bucket()).destinationKey(destinationKey)
                    .build());
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket()).key(sourceKey).build());
        } catch (SdkException e) {
            log.error("[r2] moveObject 실패 {} -> {}", sourceKey, destinationKey, e);
            throw new StorageException(STORAGE_UNAVAILABLE);
        }
    }

    @Override
    public String publicUrl(String key) {
        return properties.publicBaseUrl() + "/" + key;
    }
}
