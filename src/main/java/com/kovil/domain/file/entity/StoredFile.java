package com.kovil.domain.file.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.enums.FileStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@SuperBuilder
@Table(name = "files")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredFile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    // 벤더가 바뀌어도 키 체계는 그대로 간다 — 그래서 URL이 아니라 키를 정본으로 둔다.
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    // 클라이언트 신고값이 아니라 confirm 시점의 스토리지 실측값이다.
    @Column(name = "byte_size", nullable = false)
    private Long byteSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private FilePurpose purpose;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FileStatus status = FileStatus.ACTIVE;

    @Builder.Default
    @Column(name = "is_moved", nullable = false)
    private Boolean isMoved = false;

    // ★ INV-08 — 민감 파일은 여기가 NULL이다. 값이 들어가는 순간 불변식이 깨진다.
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }

    // 즉시 파기하지 않는다. 정리 배치가 격리 접두사로 옮기고(FileCleanupProcessor),
    // 그 접두사의 R2 수명 규칙이 기간을 채운 뒤 실제로 지운다.
    public void markDeleted(LocalDateTime at) {
        this.status = FileStatus.DELETED;
        this.deletedAt = at;
    }

    /**
     * 격리 이동 완료. <b>키까지 함께 바꾼다</b> — 행은 옮겼다는데 키가 옛 자리를 가리키면
     * 이후 조회가 없는 오브젝트를 찾는다.
     */
    public void markMoved(String movedKey) {
        this.storageKey = movedKey;
        this.isMoved = true;
    }

    /** 이미 옮겼는가 — 배치 멱등 판정. `is_moved`는 NOT NULL이지만 방어적으로 읽는다. */
    public boolean isMoved() {
        return Boolean.TRUE.equals(this.isMoved);
    }

    public boolean isSensitive() {
        return purpose.isSensitive();
    }
}
