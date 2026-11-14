package com.kovil.domain.verification.service;

import com.kovil.common.exception.MasterException;
import com.kovil.domain.verification.dto.request.AdminMatrixRowRequest;
import com.kovil.domain.verification.dto.request.AdminResidentStatusRequest;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.DocumentTypeTranslationRepository;
import com.kovil.domain.verification.repository.ResidentStatusDocumentRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.verification.repository.ResidentStatusTranslationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AdminVerificationMasterServiceTest {

    @Mock ResidentStatusRepository statusRepository;
    @Mock ResidentStatusTranslationRepository statusTranslationRepository;
    @Mock DocumentTypeRepository documentRepository;
    @Mock DocumentTypeTranslationRepository documentTranslationRepository;
    @Mock ResidentStatusDocumentRepository matrixRepository;
    @Mock EntityManager entityManager;

    private AdminVerificationMasterService service;

    @BeforeEach
    void setUp() {
        service = new AdminVerificationMasterService(statusRepository, statusTranslationRepository,
                documentRepository, documentTranslationRepository, matrixRepository, entityManager);
    }

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static String codeOf(Throwable e) {
        return ((MasterException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("같은 (신분, 문서) 쌍의 매트릭스 행은 409다")
    void duplicate_matrix_pair_is_rejected() {
        given(statusRepository.findByCode("STUDENT"))
                .willReturn(Optional.of(withId(ResidentStatus.builder().code("STUDENT").build(), 1L)));
        given(documentRepository.findByCode("PASSPORT"))
                .willReturn(Optional.of(withId(DocumentType.builder().code("PASSPORT").build(), 2L)));
        given(matrixRepository.existsByResidentStatusIdAndDocumentTypeId(1L, 2L)).willReturn(true);

        assertThatThrownBy(() -> service.createMatrixRow(
                new AdminMatrixRowRequest("STUDENT", "PASSPORT", true)))
                .extracting(AdminVerificationMasterServiceTest::codeOf).isEqualTo("MASTER_DUPLICATE");
        then(matrixRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("없는 신분 코드를 지목하면 400이다")
    void unknown_reference_is_rejected() {
        given(statusRepository.findByCode("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createMatrixRow(
                new AdminMatrixRowRequest("nope", "PASSPORT", true)))
                .extracting(AdminVerificationMasterServiceTest::codeOf)
                .isEqualTo("MASTER_REFERENCE_NOT_FOUND");
    }

    @Test
    @DisplayName("수정에서 번역을 null로 보내면 기존 번역이 유지된다")
    void null_translations_keep_existing() {
        ResidentStatus status = withId(ResidentStatus.builder().code("STUDENT").sortOrder(1).active(true).build(), 1L);
        given(statusRepository.findById(1L)).willReturn(Optional.of(status));

        service.updateStatus(1L, new AdminResidentStatusRequest("ignored", "icon", 2, true, null));

        then(statusTranslationRepository).should(never()).deleteAll(any());
        assertThat(status.getSortOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("중복 신분 코드는 409다")
    void duplicate_status_code_is_rejected() {
        given(statusRepository.existsByCode("STUDENT")).willReturn(true);

        assertThatThrownBy(() -> service.createStatus(
                new AdminResidentStatusRequest("student", null, 0, true, List.of())))
                .extracting(AdminVerificationMasterServiceTest::codeOf).isEqualTo("MASTER_DUPLICATE");
    }
}
