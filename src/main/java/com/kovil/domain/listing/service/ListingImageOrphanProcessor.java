package com.kovil.domain.listing.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.listing.repository.ListingImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

// 매물에 안 붙은 매물 사진을 폐기 표시. 그 뒤는 파일 정리 배치(격리 이동) → R2 수명 규칙.
// 유예는 폼을 연 채 사진을 올리고 저장하기까지의 여유 — 그보다 오래 연 폼의 새 사진은 저장 때 LISTING_IMAGE_INVALID.
@Component
@RequiredArgsConstructor
public class ListingImageOrphanProcessor {

    static final int BATCH_SIZE = 200;

    private final ListingImageRepository imageRepository;
    private final StoredFileRepository storedFileRepository;
    private final Clock clock;

    @Value("${file.orphan.listing-image.grace:24h}")
    private Duration grace;

    @Transactional(readOnly = true)
    public List<Long> findCandidates() {
        return imageRepository.findUnattachedImageFileIds(
                LocalDateTime.now(clock).minus(grace), PageRequest.of(0, BATCH_SIZE));
    }

    // 건마다 따로 다시 확인 — 집은 뒤 그사이 붙었으면 건너뜀. 파일 행을 잠가 새로 붙이는 저장(같은 행 공유 잠금)과 줄 세움.
    // 이미 폐기됐으면 아무것도 안 함(두 인스턴스가 같이 돌아도 무해).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean discardIfUnattached(Long fileId) {
        StoredFile file = storedFileRepository.findByIdForUpdate(fileId).orElse(null);
        if (file == null || file.getStatus() != FileStatus.ACTIVE || file.getPurpose() != FilePurpose.LISTING_IMAGE
                || imageRepository.existsByFileId(fileId)) {
            return false;
        }
        file.markDeleted(LocalDateTime.now(clock));
        return true;
    }
}
