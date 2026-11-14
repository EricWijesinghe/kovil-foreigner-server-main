package com.kovil.domain.file.service;

import com.kovil.domain.file.dto.response.FileReadUrlResponse;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.exception.FileException;
import com.kovil.common.security.AuthenticatedUser;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.infra.storage.StorageClient;
import com.kovil.infra.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static com.kovil.domain.file.exception.FileErrorCode.FILE_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FileQueryService {

    private final StorageClient storageClient;
    private final StorageProperties storageProperties;
    private final StoredFileRepository storedFileRepository;
    private final FileAccessPolicy fileAccessPolicy;
    private final UserQueryService userQueryService;

    public StoredFile getActiveFile(String fileUuid) {
        return storedFileRepository.findByUuidAndDeletedAtIsNull(fileUuid)
                .orElseThrow(() -> new FileException(FILE_NOT_FOUND));
    }

    // 민감 파일의 유일한 열람 통로다(INV-08).
    // 권한은 FileAccessPolicy가 용도별로 판정한다 — 못 보는 파일과 없는 파일을 구분하지 않는다
    // (구분하면 그 파일이 존재한다는 사실이 샌다).
    public FileReadUrlResponse issueReadUrl(String fileUuid, AuthenticatedUser principal) {
        StoredFile file = getActiveFile(fileUuid);

        // 정지·탈퇴 게이트를 다른 경로와 동일하게 지난다 — 정지된 직원의 살아있는 액세스
        // 토큰이 민감 문서 통로로 남는 것을 여기서 끊는다. 롤 판정도 토큰이 아니라 DB다
        // (강등이 토큰 수명만큼 살아남지 않게).
        User viewer = userQueryService.getActiveUser(principal.userUuid());
        if (!fileAccessPolicy.canRead(file, viewer)) {
            throw new FileException(FILE_NOT_FOUND);
        }

        Duration ttl = Duration.ofSeconds(storageProperties.readUrlTtlSeconds());

        return new FileReadUrlResponse(
                storageClient.presignGet(file.getStorageKey(), ttl), ttl.toSeconds());
    }
}
