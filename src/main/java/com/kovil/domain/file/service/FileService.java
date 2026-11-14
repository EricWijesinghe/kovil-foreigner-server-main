package com.kovil.domain.file.service;

import com.kovil.domain.file.dto.request.ConfirmUploadRequest;
import com.kovil.domain.file.dto.request.PresignUploadRequest;
import com.kovil.domain.file.dto.response.FileResponse;
import com.kovil.domain.file.dto.response.PresignedUploadResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.exception.FileException;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.storage.PresignedUpload;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.StorageProperties;
import com.kovil.infra.storage.StoredObjectMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static com.kovil.domain.file.exception.FileErrorCode.FILE_TOO_LARGE;
import static com.kovil.domain.file.exception.FileErrorCode.INVALID_FILE_PURPOSE;
import static com.kovil.domain.file.exception.FileErrorCode.INVALID_TEMP_KEY;
import static com.kovil.domain.file.exception.FileErrorCode.UNSUPPORTED_CONTENT_TYPE;
import static com.kovil.domain.file.exception.FileErrorCode.UPLOADED_FILE_NOT_FOUND;

// presign(temp/, DB 무기록) → confirm(실측 검증 + 확정 이동 + 원장 기록).
// 올리고 확정하지 않은 temp/ 고아는 스토리지 수명 정책이 지운다 — 서버가 추적하지 않는다.
//
// ★ tempKey는 **권한 증표**다: `temp/{용도}/{업로더 uuid}/{파일 uuid}.{확장자}`.
//   presign이 발급한 키만 그 경로에 오브젝트를 올릴 수 있으므로, 키에 박힌 용도·업로더가
//   곧 서명 역할을 한다. 이게 없으면 confirm이 **클라이언트가 신고한 용도를 그대로 믿게** 되어,
//   여권 스캔을 COMMUNITY_IMAGE로 확정해 공개 URL을 만들 수 있다(INV-08 정면 위반).
//
// ★ 파일 uuid를 키에 박는 이유는 하나 더 있다 — **confirm 멱등성**. 확정은 copy+delete라
//   재시도하면 temp 오브젝트가 이미 없다. 그때 같은 uuid로 이미 만들어진 원장 행을 찾아
//   그대로 돌려준다. 안 그러면 응답만 유실돼도 아무 도메인에도 안 붙은 고아 행이 영구히 남는다
//   (정리 배치가 없다 — HUMAN_TODO).
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String TEMP_PREFIX = "temp/";

    private final StorageClient storageClient;
    private final StorageProperties storageProperties;
    private final StoredFileRepository storedFileRepository;
    private final UserQueryService userQueryService;

    // DB에 아무것도 남기지 않는다. 여기서 행을 만들면 "올리다 만" 행이 계속 쌓인다.
    public PresignedUploadResponse presign(String userUuid, PresignUploadRequest request) {
        FilePurpose purpose = uploadablePurpose(request.purpose());
        if (!purpose.allows(request.contentType())) {
            throw new FileException(UNSUPPORTED_CONTENT_TYPE);
        }

        User uploader = userQueryService.getActiveUser(userUuid);
        String tempKey = "%s%s/%s/%s.%s".formatted(
                TEMP_PREFIX, purpose.name(), uploader.getUuid(),
                UUID.randomUUID(), purpose.extensionOf(request.contentType()));
        PresignedUpload presigned = storageClient.presignPut(
                tempKey, request.contentType(),
                Duration.ofSeconds(storageProperties.presignTtlSeconds()));

        return PresignedUploadResponse.from(presigned);
    }

    // 외부 호출(head·move)이 들어 있어 트랜잭션을 넓게 걸지 않는다 —
    // 스토리지 지연이 DB 커넥션을 붙잡으면 안 된다. 저장만 트랜잭션이다.
    public FileResponse confirm(String userUuid, ConfirmUploadRequest request) {
        FilePurpose purpose = uploadablePurpose(request.purpose());
        User uploader = userQueryService.getActiveUser(userUuid);

        // 키에 박힌 용도·업로더가 신고값과 같아야 한다 — 다르면 남의 오브젝트이거나 용도 위조다.
        TempKey tempKey = TempKey.parse(request.tempKey());
        if (tempKey.purpose() != purpose || !tempKey.uploaderUuid().equals(uploader.getUuid())) {
            throw new FileException(INVALID_TEMP_KEY);
        }

        // 이미 확정된 키다(응답만 유실된 재시도). 새 행을 만들지 않고 그때 만든 것을 돌려준다.
        Optional<StoredFile> already = storedFileRepository.findByUuidAndDeletedAtIsNull(tempKey.fileUuid());
        if (already.isPresent()) {
            return FileResponse.from(already.get());
        }

        // 클라이언트 신고값이 아니라 스토리지 실측값으로 판정한다.
        Optional<StoredObjectMetadata> tempMetadata = storageClient.headObject(request.tempKey());

        String contentType;
        Long contentLength;
        String finalKey;
        if (tempMetadata.isPresent()) {
            contentType = normalize(tempMetadata.get().contentType());
            contentLength = tempMetadata.get().contentLength();
            finalKey = finalKey(purpose, tempKey.fileUuid(), contentType);
            validateObject(purpose, contentType, contentLength);
            storageClient.moveObject(request.tempKey(), finalKey);
        } else {
            // 크래시 창 복구(AUDIT_2026_08_26): 이전 confirm이 move까지 끝내고 행 저장 전에
            // 죽으면 temp는 없고 final만 있다. finalKey가 파일 uuid로 **결정적**이라 재시도가
            // 그 오브젝트를 도로 찾을 수 있다 — 없으면 그때가 진짜 "업로드 없음"이다.
            Recovered recovered = probeFinal(purpose, tempKey.fileUuid())
                    .orElseThrow(() -> new FileException(UPLOADED_FILE_NOT_FOUND));
            contentType = normalize(recovered.metadata().contentType());
            contentLength = recovered.metadata().contentLength();
            finalKey = recovered.key();
            validateObject(purpose, contentType, contentLength);
        }

        return FileResponse.from(save(tempKey.fileUuid(), purpose, finalKey, contentType,
                contentLength, request.originalName(), uploader.getId()));
    }

    // 은퇴한 용도는 없는 용도와 같은 답을 준다. confirm 도 막아야 은퇴 전에 받아 둔 presign 으로
    // 새 행이 생기지 않는다.
    private FilePurpose uploadablePurpose(String raw) {
        FilePurpose purpose = FilePurpose.from(raw);
        if (purpose.isRetired()) {
            throw new FileException(INVALID_FILE_PURPOSE);
        }
        return purpose;
    }

    private void validateObject(FilePurpose purpose, String contentType, Long contentLength) {
        if (!purpose.allows(contentType)) {
            throw new FileException(UNSUPPORTED_CONTENT_TYPE);
        }
        if (contentLength == null || contentLength > purpose.getMaxSizeBytes()) {
            throw new FileException(FILE_TOO_LARGE);
        }
    }

    private record Recovered(String key, StoredObjectMetadata metadata) {}

    // 용도가 허용하는 확장자는 한 줌이다(IMAGE 3종 안팎) — 복구 경로에서만 도는 소량 조회다.
    private Optional<Recovered> probeFinal(FilePurpose purpose, String fileUuid) {
        return purpose.getAllowedContentTypes().values().stream().distinct()
                .map(ext -> "%s/%s.%s".formatted(purpose.getPathPrefix(), fileUuid, ext))
                .flatMap(key -> storageClient.headObject(key)
                        .map(meta -> new Recovered(key, meta)).stream())
                .findFirst();
    }

    /** `temp/{용도}/{업로더 uuid}/{파일 uuid}.{확장자}` — 형식이 어긋나면 전부 INVALID_TEMP_KEY다. */
    private record TempKey(FilePurpose purpose, String uploaderUuid, String fileUuid) {

        static TempKey parse(String raw) {
            if (raw == null || !raw.startsWith(TEMP_PREFIX) || raw.contains("..")) {
                throw new FileException(INVALID_TEMP_KEY);
            }
            String[] parts = raw.substring(TEMP_PREFIX.length()).split("/");
            if (parts.length != 3) {
                throw new FileException(INVALID_TEMP_KEY);
            }
            int dot = parts[2].lastIndexOf('.');
            if (dot <= 0) {
                throw new FileException(INVALID_TEMP_KEY);
            }
            try {
                return new TempKey(FilePurpose.valueOf(parts[0]), parts[1], parts[2].substring(0, dot));
            } catch (IllegalArgumentException e) {
                throw new FileException(INVALID_TEMP_KEY);
            }
        }
    }

    @Transactional
    protected StoredFile save(String fileUuid, FilePurpose purpose, String key, String contentType,
                              long byteSize, String originalName, Long uploaderId) {
        return storedFileRepository.save(StoredFile.builder()
                // tempKey의 uuid를 그대로 원장 식별자로 쓴다 — 재시도가 같은 행을 찾게 하는 축이다.
                .uuid(fileUuid)
                .storageKey(key)
                .originalName(originalName)
                .contentType(contentType)
                .byteSize(byteSize)
                .purpose(purpose)
                .uploaderId(uploaderId)
                // ★ INV-08 — 민감 파일은 공개 URL을 만들지 않는다. 여기가 유일한 분기점이다.
                .fileUrl(purpose.isSensitive() ? null : storageClient.publicUrl(key))
                .build());
    }

    // 날짜를 키에 넣어 한 프리픽스에 오브젝트가 무한히 쌓이지 않게 한다.
    // 파일 uuid 로 **결정적**이다(AUDIT) — 무작위 키는 "move 후 저장 전 죽음"을 영구 유실로
    // 만들었다(재시도가 오브젝트를 못 찾는다). 연/월 폴더는 사람 보기용이었고 결정성이 이긴다.
    // 이미 저장된 구 키(연/월/무작위)는 DB가 기억하므로 레이아웃 변경은 신규 파일에만 적용된다.
    private String finalKey(FilePurpose purpose, String fileUuid, String contentType) {
        return "%s/%s.%s".formatted(purpose.getPathPrefix(), fileUuid, purpose.extensionOf(contentType));
    }

    // 스토리지가 "image/png; charset=..." 형태로 줄 수 있다.
    private String normalize(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase();
    }
}
