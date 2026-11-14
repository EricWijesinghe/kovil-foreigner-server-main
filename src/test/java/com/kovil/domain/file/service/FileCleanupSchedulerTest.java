package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/*
 * ★ 한 파일이 막혀도 회차가 멈추면 안 된다. 스토리지가 특정 오브젝트 하나에서 5xx 를 내면
 *   그 뒤 199건이 통째로 밀리고, 매일 같은 자리에서 다시 막힌다 — 대기실이 영원히 안 비워진다.
 */
@ExtendWith(MockitoExtension.class)
class FileCleanupSchedulerTest {

    @Mock FileCleanupProcessor processor;

    @InjectMocks FileCleanupScheduler scheduler;

    private StoredFile file(Long id) {
        StoredFile f = StoredFile.builder()
                .uuid("f-" + id).storageKey("verification-docs/" + id + ".jpg")
                .contentType("image/jpeg").byteSize(1L)
                .purpose(FilePurpose.VERIFICATION_DOC).build();
        try {
            var field = StoredFile.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(f, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        f.markDeleted(LocalDateTime.now());
        return f;
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지는 계속 옮긴다")
    void one_failure_does_not_stop_the_run() {
        given(processor.findMovable()).willReturn(List.of(file(1L), file(2L), file(3L)));
        willThrow(new RuntimeException("storage 5xx")).given(processor).move(2L);

        scheduler.run();

        then(processor).should().move(1L);
        then(processor).should().move(2L);
        then(processor).should().move(3L);
    }

    // 대상이 없으면 스토리지를 건드리지 않는다 — 매일 도는 배치라 빈 회차가 대부분이다.
    @Test
    @DisplayName("옮길 것이 없으면 아무 일도 하지 않는다")
    void empty_run_is_noop() {
        given(processor.findMovable()).willReturn(List.of());

        scheduler.run();

        then(processor).should(never()).move(ArgumentMatchers.anyLong());
    }
}
