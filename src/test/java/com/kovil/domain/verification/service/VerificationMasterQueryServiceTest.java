package com.kovil.domain.verification.service;

import com.kovil.domain.verification.dto.response.RequiredDocumentResponse;
import com.kovil.domain.verification.dto.response.ResidentStatusResponse;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.DocumentTypeTranslation;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.entity.ResidentStatusDocument;
import com.kovil.domain.verification.entity.ResidentStatusTranslation;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.DocumentTypeTranslationRepository;
import com.kovil.domain.verification.repository.ResidentStatusDocumentRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.verification.repository.ResidentStatusTranslationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThatNoException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.groups.Tuple;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class VerificationMasterQueryServiceTest {

    @Mock ResidentStatusRepository residentStatusRepository;
    @Mock ResidentStatusTranslationRepository residentStatusTranslationRepository;
    @Mock ResidentStatusDocumentRepository residentStatusDocumentRepository;
    @Mock DocumentTypeRepository documentTypeRepository;
    @Mock DocumentTypeTranslationRepository documentTypeTranslationRepository;

    @InjectMocks VerificationMasterQueryService service;

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

    private static ResidentStatus status(Long id, String code) {
        return withId(ResidentStatus.builder().code(code).iconKey(code.toLowerCase()).build(), id);
    }

    private static ResidentStatusTranslation statusTr(Long statusId, String lang, String name) {
        return ResidentStatusTranslation.builder()
                .residentStatusId(statusId).languageCode(lang).name(name).description(name + " 설명").build();
    }

    @Nested
    @DisplayName("신분 목록")
    class Statuses {

        @Test
        @DisplayName("요청 언어 번역이 있으면 그것을 쓴다")
        void uses_requested_language() {
            given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                    .willReturn(List.of(status(1L, "STUDENT")));
            given(residentStatusTranslationRepository.findByLanguageCodeIn(any()))
                    .willReturn(List.of(statusTr(1L, "en", "Student"), statusTr(1L, "ko", "유학생")));

            assertThat(service.findActiveStatuses("ko"))
                    .extracting(ResidentStatusResponse::name).containsExactly("유학생");
        }

        // 마스터에 인라인 이름이 없다 — 번역이 유일한 출처라 폴백이 더 중요하다.
        @Test
        @DisplayName("요청 언어 번역이 없으면 기본 언어(en)로 떨어진다")
        void falls_back_to_default_language() {
            given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                    .willReturn(List.of(status(1L, "STUDENT")));
            given(residentStatusTranslationRepository.findByLanguageCodeIn(any()))
                    .willReturn(List.of(statusTr(1L, "en", "Student")));

            assertThat(service.findActiveStatuses("vi"))
                    .extracting(ResidentStatusResponse::name).containsExactly("Student");
        }

        // 번역이 없다고 항목을 감추면 사용자가 그 신분을 아예 고를 수 없게 된다.
        @Test
        @DisplayName("번역이 아예 없어도 항목을 감추지 않고 코드라도 보여준다")
        void never_hides_untranslated_status() {
            given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                    .willReturn(List.of(status(1L, "STUDENT")));
            given(residentStatusTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

            assertThat(service.findActiveStatuses("ko"))
                    .extracting(ResidentStatusResponse::code, ResidentStatusResponse::name)
                    .containsExactly(Tuple.tuple("STUDENT", "STUDENT"));
        }

        // 번역을 신분마다 조회하면 왕복이 신분 수만큼 늘어난다.
        @Test
        @DisplayName("번역 조회는 신분 수와 무관하게 한 번이다")
        void translations_read_once() {
            given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc())
                    .willReturn(List.of(status(1L, "STUDENT"), status(2L, "WORKER"), status(3L, "OTHER")));
            given(residentStatusTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

            service.findActiveStatuses("ko");

            then(residentStatusTranslationRepository).should().findByLanguageCodeIn(any());
            then(residentStatusTranslationRepository).shouldHaveNoMoreInteractions();
        }
    }

    @Nested
    @DisplayName("요구 문서")
    class Requirements {

        @BeforeEach
        void etcAbsentByDefault() {
            // 기타 슬롯이 없는 환경이 기본 무대다 — 있는 경우는 개별 테스트가 명시한다.
            Mockito.lenient().when(documentTypeRepository.findByCodeAndActiveTrue("ETC"))
                    .thenReturn(Optional.empty());
        }

        private void givenStudent() {
            given(residentStatusRepository.findByCodeAndActiveTrue("STUDENT"))
                    .willReturn(Optional.of(status(1L, "STUDENT")));
        }

        private ResidentStatusDocument row(Long typeId, boolean required, int order) {
            return ResidentStatusDocument.builder()
                    .residentStatusId(1L).documentTypeId(typeId).isRequired(required).sortOrder(order).build();
        }

        private DocumentType type(Long id, String code, int maxFiles, boolean sensitive) {
            return type(id, code, maxFiles, sensitive, 0);
        }

        private DocumentType type(Long id, String code, int maxFiles, boolean sensitive, int sortOrder) {
            return withId(DocumentType.builder()
                    .code(code).maxFiles(maxFiles).isSensitive(sensitive).sortOrder(sortOrder).build(), id);
        }

        // ★ INV-03 — "무엇을 내나"는 매트릭스가 답하고, **순서는 문서 유형 축이 답한다**
        //   (단일 진실 — BE_REQUEST_08_20_02 §2-A). 매트릭스 sort_order와 축 sortOrder를
        //   **서로 반대로** 두어야 판별력이 생긴다 — 두 순서가 우연히 같은 픽스처는
        //   변경을 해도 안 해도 그린이라 아무것도 고정하지 못한다.
        @Test
        @DisplayName("요건 순서는 매트릭스가 아니라 문서 유형 축이 정한다")
        void requirements_order_comes_from_document_type_axis() {
            givenStudent();
            // 매트릭스 순서: PASSPORT(1) → ENROLLMENT_CERT(2). 축 순서는 반대다.
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(10L, true, 1), row(11L, false, 2)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any()))
                    .willReturn(List.of(type(10L, "PASSPORT", 2, true, 9),
                            type(11L, "ENROLLMENT_CERT", 1, false, 1)));
            given(documentTypeTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of(
                    DocumentTypeTranslation.builder().documentTypeId(10L).languageCode("ko")
                            .name("여권").guideNote("사진면 전체가 보이게").build()));

            List<RequiredDocumentResponse> result = service.findRequirements("STUDENT", "ko");

            assertThat(result).extracting(RequiredDocumentResponse::documentCode)
                    .containsExactly("ENROLLMENT_CERT", "PASSPORT");
            assertThat(result.get(1).required()).isTrue();
            assertThat(result.get(1).maxFiles()).isEqualTo(2);
            assertThat(result.get(1).sensitive()).isTrue();
            assertThat(result.get(1).guideNote()).isEqualTo("사진면 전체가 보이게");
            assertThat(result.get(0).required()).isFalse();
        }

        // 매트릭스 행이 남아 있어도 문서 종류를 비활성화하면 제출 화면에서 빠져야 한다.
        @Test
        @DisplayName("비활성 문서 종류는 요건에서 빠진다")
        void inactive_document_type_is_dropped() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(10L, true, 1), row(99L, true, 2)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any()))
                    .willReturn(List.of(type(10L, "PASSPORT", 1, true)));
            given(documentTypeTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

            assertThat(service.findRequirements("STUDENT", "ko"))
                    .extracting(RequiredDocumentResponse::documentCode).containsExactly("PASSPORT");
        }

        /*
         * ★ 매트릭스 유래 요건이 전부 비활성으로 걸러지면 **빈 목록**이다 — ETC 폴백을 붙이면
         *   [ETC(선택)]만 남은 목록이 제출 가드(requirements.isEmpty())를 우회해 서류 0장 제출이
         *   통과한다(적대적 리뷰 2026-09-02, 8라운드). 가드를 빼면 이 테스트가 죽는다.
         */
        @Test
        @DisplayName("요건이 전부 비활성이면 ETC 폴백 없이 빈 목록이다 — 서류 0장 우회를 막는다")
        void all_inactive_requirements_return_empty_without_etc_fallback() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(10L, true, 1)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any())).willReturn(List.of());
            Mockito.lenient()
                    .when(documentTypeRepository.findByCodeAndActiveTrue("ETC"))
                    .thenReturn(Optional.of(type(999L, "ETC", 99, true)));

            assertThat(service.findRequirements("STUDENT", "ko")).isEmpty();
        }

        // 요건 서류만으로 본인을 증명하기 어려운 신분이 있다 — 무엇이든 덧붙일 통로를 항상 연다.
        @Test
        @DisplayName("기타 증빙(ETC) 슬롯이 요건 마지막에 선택으로 붙는다")
        void extra_slot_is_appended_last() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(10L, true, 1)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any()))
                    .willReturn(List.of(type(10L, "PASSPORT", 2, true)));
            given(documentTypeTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of(
                    DocumentTypeTranslation.builder().documentTypeId(50L).languageCode("ko")
                            .name("기타 증빙 서류").guideNote("무엇이든 좋습니다").build()));
            given(documentTypeRepository.findByCodeAndActiveTrue("ETC"))
                    .willReturn(Optional.of(type(50L, "ETC", 5, true)));

            List<RequiredDocumentResponse> result = service.findRequirements("STUDENT", "ko");

            assertThat(result).extracting(RequiredDocumentResponse::documentCode)
                    .containsExactly("PASSPORT", "ETC");
            RequiredDocumentResponse extra = result.get(1);
            assertThat(extra.required()).isFalse();
            assertThat(extra.maxFiles()).isEqualTo(5);
            assertThat(extra.name()).isEqualTo("기타 증빙 서류");
            assertThat(extra.guideNote()).isEqualTo("무엇이든 좋습니다");
        }

        // 관리자가 ETC를 특정 신분의 매트릭스 행으로 승격시키면 그 행(필수 플래그 포함)이 이긴다.
        @Test
        @DisplayName("매트릭스에 이미 ETC가 있으면 중복으로 붙이지 않는다")
        void promoted_extra_is_not_duplicated() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(50L, true, 1)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any()))
                    .willReturn(List.of(type(50L, "ETC", 5, true)));
            given(documentTypeTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

            List<RequiredDocumentResponse> result = service.findRequirements("STUDENT", "ko");

            assertThat(result).extracting(RequiredDocumentResponse::documentCode).containsExactly("ETC");
            assertThat(result.get(0).required()).isTrue();
        }

        // ★ PO 확정(2026-08-20) — ETC는 승격 여부와 무관하게 **항상 마지막**이다. 축 sortOrder를
        //   아무리 앞으로 줘도 자리는 바뀌지 않는다(999 센티널 의존도 이것으로 끝났다).
        @Test
        @DisplayName("승격된 ETC도 축 순서와 무관하게 항상 마지막이다")
        void promoted_extra_stays_last_regardless_of_axis_order() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of(row(10L, true, 1), row(50L, true, 2)));
            given(documentTypeRepository.findByIdInAndActiveTrue(any()))
                    .willReturn(List.of(type(10L, "PASSPORT", 2, true, 9),
                            // 축에서 ETC가 맨 앞(sortOrder 1)이어도
                            type(50L, "ETC", 5, true, 1)));
            given(documentTypeTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());

            List<RequiredDocumentResponse> result = service.findRequirements("STUDENT", "ko");

            assertThat(result).extracting(RequiredDocumentResponse::documentCode)
                    .containsExactly("PASSPORT", "ETC");
            assertThat(result.get(1).required()).isTrue();
        }

        @Test
        @DisplayName("요건이 비어 있으면 빈 목록이고 문서 조회를 하지 않는다")
        void empty_matrix_short_circuits() {
            givenStudent();
            given(residentStatusDocumentRepository.findByResidentStatusId(1L))
                    .willReturn(List.of());

            assertThat(service.findRequirements("STUDENT", "ko")).isEmpty();
            then(documentTypeRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("없거나 비활성인 신분은 404다")
        void unknown_status_is_404() {
            given(residentStatusRepository.findByCodeAndActiveTrue("GHOST")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.findRequirements("GHOST", "ko"))
                    .isInstanceOf(VerificationException.class)
                    .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                    .isEqualTo("RESIDENT_STATUS_NOT_FOUND");
        }
    }

    // ★ 실측에서 잡은 결함: Set.of("en","en")이 duplicate element로 터졌다.
    // 기존 테스트가 ko·vi만 봐서 **기본 언어인 en 경로가 통째로 비어 있었다.**
    @Test
    @DisplayName("기본 언어(en)로 요청해도 터지지 않는다 — 조회 언어가 하나로 접힌다")
    void default_language_request_does_not_throw() {
        given(residentStatusTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());
        given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).willReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.findActiveStatuses("en"));

        ArgumentCaptor<Collection<String>> captor =
                ArgumentCaptor.forClass(Collection.class);
        then(residentStatusTranslationRepository).should().findByLanguageCodeIn(captor.capture());
        assertThat(captor.getValue()).containsExactly("en");
    }

    @Test
    @DisplayName("언어가 null이거나 빈 문자열이어도 터지지 않는다")
    void blank_language_does_not_throw() {
        given(residentStatusTranslationRepository.findByLanguageCodeIn(any())).willReturn(List.of());
        given(residentStatusRepository.findByActiveTrueOrderBySortOrderAscCodeAsc()).willReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.findActiveStatuses((String) null));
        assertThatNoException().isThrownBy(() -> service.findActiveStatuses("  "));
    }
}
