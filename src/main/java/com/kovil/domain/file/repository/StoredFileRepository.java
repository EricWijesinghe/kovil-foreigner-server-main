package com.kovil.domain.file.repository;

import com.kovil.domain.file.entity.StoredFile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kovil.domain.file.enums.FileStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByUuidAndDeletedAtIsNull(String uuid);

    // 매물 사진을 새로 붙일 때 공유 잠금 — 미첨부 사진 정리 배치(findByIdForUpdate)와 같은 행에서 줄 세움.
    // 안 잠그면 배치가 폐기 표시한 직후 저장이 그 파일을 붙여, 매물에 지워질 사진이 남음.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT f FROM StoredFile f WHERE f.uuid = :uuid AND f.deletedAt IS NULL")
    Optional<StoredFile> findForShareByUuid(@Param("uuid") String uuid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StoredFile f WHERE f.id = :id")
    Optional<StoredFile> findByIdForUpdate(@Param("id") Long id);

    // 매물에서 사진을 뺄 때 — 같은 줄 세우기. id 순으로 잠가 두 저장이 서로 다른 순서로 잡지 않게 함.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM StoredFile f WHERE f.id IN :ids ORDER BY f.id")
    List<StoredFile> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);

    /**
     * 정리 배치가 집을 대상 — <b>지워졌다고 표시됐지만 아직 옮기지 못한 것</b>.
     *
     * <p>★ 이 두 컬럼이 멱등 축이다(`idx_files_cleanup`). 배치가 이동 도중 죽어도 다음 회차가
     * 같은 행을 다시 집는다 — 이미 옮긴 것은 `is_moved=1` 이라 두 번 옮기지 않는다.
     *
     * <p>한 번에 다 집지 않는 이유: 스토리지 복사는 건당 왕복이라 수만 건을 한 트랜잭션에
     * 담으면 커넥션을 오래 쥔다. 회차를 나눠 조금씩 비운다.
     */
    List<StoredFile> findTop200ByStatusAndIsMovedFalseOrderByIdAsc(FileStatus status);

    /**
     * 공개 URL 로 찾는다 — 비민감 파일은 도메인이 uuid 가 아니라 URL 을 들고 있다
     * (`community_post_images.image_url`). 민감 파일은 `file_url` 이 NULL 이라 여기 걸리지 않는다.
     */
    List<StoredFile> findByFileUrlInAndDeletedAtIsNull(Collection<String> fileUrls);

    /**
     * URL + <b>업로더</b>로 찾는다 — 사용자가 보낸 URL 을 근거로 파일을 버릴 때 쓴다.
     *
     * <p>★ 업로더 축이 없으면 <b>남의 파일을 지우는 통로</b>가 된다. 커뮤니티 이미지는 공개
     * URL 이라 누구나 남의 글에서 주소를 읽어 자기 글에 붙일 수 있고, 그 글을 지우는 순간
     * 원래 주인의 파일이 폐기 표시된다.
     */
    List<StoredFile> findByFileUrlInAndUploaderIdAndDeletedAtIsNull(
            Collection<String> fileUrls, Long uploaderId);

    // id 묶음으로 찾는다 — 도메인이 FK 로 **id** 를 들고 있는데 응답에는 uuid·URL 을 실어야 할 때.
    //
    // ★ 매물 사진이 그 모양이다(`listing_images.file_id`). 건별 조회면 목록 한 번에 N+1 이 된다.
    List<StoredFile> findByIdInAndDeletedAtIsNull(Collection<Long> ids);
}
