package com.kovil.domain.verification.service;

import com.kovil.domain.country.entity.Country;
import com.kovil.domain.country.repository.CountryRepository;
import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.file.repository.StoredFileRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.service.UserQueryService;
import com.kovil.domain.verification.dto.request.VerificationSubmitRequest;
import com.kovil.domain.verification.dto.response.RequiredDocumentResponse;
import com.kovil.domain.verification.entity.DocumentType;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.entity.ResidentStatus;
import com.kovil.domain.verification.enums.VerificationStatus;
import com.kovil.domain.verification.exception.VerificationException;
import com.kovil.domain.verification.repository.DocumentTypeRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.verification.repository.ResidentStatusRepository;
import com.kovil.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class VerificationSubmitServiceTest {

    private static final String UUID = "user-uuid";
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");

    @Mock UserQueryService userQueryService;
    @Mock UserRepository userRepository;
    @Mock CountryRepository countryRepository;
    @Mock ResidentStatusRepository residentStatusRepository;
    @Mock DocumentTypeRepository documentTypeRepository;
    @Mock VerificationMasterQueryService masterQueryService;
    @Mock ForeignerVerificationRepository verificationRepository;
    @Mock ForeignerVerificationDocumentRepository documentRepository;
    @Mock StoredFileRepository storedFileRepository;

    private VerificationSubmitService service;

    @BeforeEach
    void setUp() {
        service = new VerificationSubmitService(userQueryService, userRepository, countryRepository,
                residentStatusRepository, documentTypeRepository, masterQueryService,
                verificationRepository, documentRepository, storedFileRepository,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
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

    private VerificationSubmitRequest request(List<VerificationSubmitRequest.SubmittedDocument> docs) {
        return new VerificationSubmitRequest("STUDENT", "NGUYEN VAN A",
                LocalDate.of(1998, 3, 15), "vn", docs);
    }

    private static VerificationSubmitRequest.SubmittedDocument doc(String code, String fileUuid) {
        return new VerificationSubmitRequest.SubmittedDocument(code, fileUuid);
    }

    private void givenBasics() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
        given(verificationRepository.findFirstByUserIdOrderByIdDesc(7L)).willReturn(Optional.empty());
        given(residentStatusRepository.findByCodeAndActiveTrue("STUDENT"))
                .willReturn(Optional.of(withId(ResidentStatus.builder().code("STUDENT").build(), 1L)));
        given(countryRepository.findByCodeAndIsActiveTrue("VN"))
                .willReturn(Optional.of(withId(Country.builder().code("VN").nameEn("Vietnam").build(), 11L)));
    }

    private void givenRequirements(RequiredDocumentResponse... reqs) {
        given(masterQueryService.findRequirements("STUDENT", "en")).willReturn(List.of(reqs));
    }

    private static RequiredDocumentResponse req(String code, boolean required, int maxFiles) {
        return new RequiredDocumentResponse(code, code, "guide", required, maxFiles, true);
    }

    private void givenFile(String uuid, Long id, Long uploaderId, FilePurpose purpose) {
        given(storedFileRepository.findByUuidAndDeletedAtIsNull(uuid)).willReturn(Optional.of(withId(
                StoredFile.builder().storageKey("k").contentType("image/png").byteSize(1L)
                        .purpose(purpose).uploaderId(uploaderId).build(), id)));
    }

    private void givenSaves() {
        given(verificationRepository.save(any(ForeignerVerification.class)))
                .willAnswer(inv -> withId(inv.getArgument(0), 100L));
        given(documentRepository.save(any(ForeignerVerificationDocument.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(documentTypeRepository.findAll()).willReturn(List.of(
                withId(DocumentType.builder().code("PASSPORT").maxFiles(2).build(), 10L),
                withId(DocumentType.builder().code("ENROLLMENT_CERT").maxFiles(1).build(), 11L),
                withId(DocumentType.builder().code("ETC").maxFiles(5).build(), 50L)));
    }

    // FR-203 — 마스터가 바뀌어도 "그때 무엇을 요구했나"가 남아야 심사 근거가 성립한다.
    @Test
    @DisplayName("제출 시점의 신분 코드와 요건 목록을 스냅샷으로 박는다")
    void snapshots_status_and_requirements() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2), req("ENROLLMENT_CERT", false, 1));
        givenFile("f1", 1L, 7L, FilePurpose.VERIFICATION_DOC);
        givenSaves();

        service.submit(UUID, request(List.of(doc("PASSPORT", "f1"))));

        ArgumentCaptor<ForeignerVerification> captor =
                ArgumentCaptor.forClass(ForeignerVerification.class);
        then(verificationRepository).should().save(captor.capture());
        ForeignerVerification saved = captor.getValue();

        assertThat(saved.getResidentStatusCode()).isEqualTo("STUDENT");
        assertThat(saved.getRequiredDocumentCodes())
                .contains("PASSPORT").contains("ENROLLMENT_CERT");
        assertThat(saved.getStatus()).isEqualTo(VerificationStatus.PENDING_REVIEW);
        assertThat(saved.getFullName()).isEqualTo("NGUYEN VAN A");
    }

    // FR-204 — 어떤 서류가 빠졌는지 알려주지 않으면 사용자가 화면을 처음부터 다시 훑어야 한다.
    // ★ PO 확정(2026-08-20) — 스냅샷 순서는 업로드 순이 아니라 **요건 화면 순서**다.
    //   유저 앱이 배열을 업로드 순으로 만들므로(키 삽입 순), 서버가 재부여하지 않으면
    //   심사 화면의 서류 순서가 사람마다 제각각이 된다. 같은 코드 안에서는 업로드 순서 유지.
    @Test
    @DisplayName("업로드 역순으로 제출해도 스냅샷 sortOrder는 요건 순서를 따른다")
    void snapshot_order_follows_requirements_not_upload() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2), req("ENROLLMENT_CERT", true, 1));
        givenFile("f-cert", 3L, 7L, FilePurpose.VERIFICATION_DOC);
        givenFile("f-pass-2", 2L, 7L, FilePurpose.VERIFICATION_DOC);
        givenFile("f-pass-1", 1L, 7L, FilePurpose.VERIFICATION_DOC);
        givenSaves();

        // 업로드(=배열) 순서: 재학증명 → 여권2 → 여권1. 요건 순서는 여권 → 재학증명.
        service.submit(UUID, request(List.of(
                doc("ENROLLMENT_CERT", "f-cert"), doc("PASSPORT", "f-pass-2"), doc("PASSPORT", "f-pass-1"))));

        ArgumentCaptor<ForeignerVerificationDocument> captor =
                ArgumentCaptor.forClass(ForeignerVerificationDocument.class);
        then(documentRepository).should(Mockito.times(3)).save(captor.capture());
        List<ForeignerVerificationDocument> saved = captor.getAllValues();

        assertThat(saved).extracting(ForeignerVerificationDocument::getDocumentTypeCode)
                .containsExactly("PASSPORT", "PASSPORT", "ENROLLMENT_CERT");
        assertThat(saved).extracting(ForeignerVerificationDocument::getSortOrder)
                .containsExactly(0, 1, 2);
        // 같은 코드(여권 2장)는 업로드 순서가 유지된다 — 안정 정렬.
        assertThat(saved.get(0).getFileId()).isEqualTo(2L);
        assertThat(saved.get(1).getFileId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("필수 서류가 빠지면 누락 코드를 담아 거절한다")
    void missing_required_documents_carry_codes() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2), req("ENROLLMENT_CERT", true, 1));

        assertThatThrownBy(() -> service.submit(UUID, request(List.of(doc("PASSPORT", "f1")))))
                .isInstanceOf(VerificationSubmitService.MissingDocumentsException.class)
                .extracting(e -> ((VerificationSubmitService.MissingDocumentsException) e)
                        .getMissingDocumentCodes())
                .isEqualTo(List.of("ENROLLMENT_CERT"));

        then(verificationRepository).should(never()).save(any());
        // 요건 검증이 파일 조회보다 먼저다 — 어차피 거절할 요청으로 스토리지·DB를 건드리지 않는다.
        then(storedFileRepository).shouldHaveNoInteractions();
    }

    // 기타 증빙은 요건을 대체하지 않고 덧붙는다 — 필수 서류 검증은 그대로 돈다.
    @Test
    @DisplayName("기타 증빙(ETC)을 요건 서류에 덧붙여 낼 수 있다")
    void extra_documents_are_accepted() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2), req("ETC", false, 5));
        givenFile("f1", 1L, 7L, FilePurpose.VERIFICATION_DOC);
        givenFile("e1", 2L, 7L, FilePurpose.VERIFICATION_DOC);
        givenFile("e2", 3L, 7L, FilePurpose.VERIFICATION_DOC);
        givenSaves();

        service.submit(UUID, request(List.of(
                doc("PASSPORT", "f1"), doc("ETC", "e1"), doc("ETC", "e2"))));

        ArgumentCaptor<ForeignerVerificationDocument> docs =
                ArgumentCaptor.forClass(ForeignerVerificationDocument.class);
        then(documentRepository).should(Mockito.times(3)).save(docs.capture());
        assertThat(docs.getAllValues())
                .extracting(ForeignerVerificationDocument::getDocumentTypeCode)
                .containsExactly("PASSPORT", "ETC", "ETC");

        ArgumentCaptor<ForeignerVerification> verification =
                ArgumentCaptor.forClass(ForeignerVerification.class);
        then(verificationRepository).should().save(verification.capture());
        assertThat(verification.getValue().getRequiredDocumentCodes()).contains("ETC");
    }

    @Test
    @DisplayName("선택 서류는 없어도 통과한다")
    void optional_documents_are_not_required() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2), req("ENROLLMENT_CERT", false, 1));
        givenFile("f1", 1L, 7L, FilePurpose.VERIFICATION_DOC);
        givenSaves();

        assertThat(service.submit(UUID, request(List.of(doc("PASSPORT", "f1")))).status())
                .isEqualTo("PENDING_REVIEW");
    }

    // FR-207 — 심사 중에는 새로 내지 못한다.
    @Test
    @DisplayName("심사 중이면 재제출을 막는다")
    void pending_blocks_resubmission() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
        given(verificationRepository.findFirstByUserIdOrderByIdDesc(7L))
                .willReturn(Optional.of(ForeignerVerification.builder()
                        .status(VerificationStatus.PENDING_REVIEW).build()));

        assertThatThrownBy(() -> service.submit(UUID, request(List.of())))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_PENDING");
    }

    @Test
    @DisplayName("반려된 뒤에는 새 행으로 다시 낼 수 있다")
    void rejected_allows_new_submission() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
        given(verificationRepository.findFirstByUserIdOrderByIdDesc(7L))
                .willReturn(Optional.of(ForeignerVerification.builder()
                        .status(VerificationStatus.REJECTED).build()));
        given(residentStatusRepository.findByCodeAndActiveTrue("STUDENT"))
                .willReturn(Optional.of(withId(ResidentStatus.builder().code("STUDENT").build(), 1L)));
        given(countryRepository.findByCodeAndIsActiveTrue("VN"))
                .willReturn(Optional.of(withId(Country.builder().code("VN").nameEn("Vietnam").build(), 11L)));
        givenRequirements(req("PASSPORT", true, 2));
        givenFile("f1", 1L, 7L, FilePurpose.VERIFICATION_DOC);
        givenSaves();

        service.submit(UUID, request(List.of(doc("PASSPORT", "f1"))));

        then(verificationRepository).should().save(any(ForeignerVerification.class));
    }

    // 남이 올린 파일 uuid를 넣어 남의 여권을 자기 신청에 붙일 수 있으면 안 된다.
    @Test
    @DisplayName("남이 올린 파일은 제출에 쓸 수 없다")
    void cannot_use_someone_elses_file() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2));
        givenFile("f1", 1L, 99L, FilePurpose.VERIFICATION_DOC);

        assertThatThrownBy(() -> service.submit(UUID, request(List.of(doc("PASSPORT", "f1")))))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_FILE_INVALID");
    }

    // 공개 URL이 있는 파일이 섞이면 INV-08이 깨진다.
    @Test
    @DisplayName("용도가 인증 문서가 아닌 파일은 거절한다")
    void rejects_wrong_purpose_file() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2));
        givenFile("f1", 1L, 7L, FilePurpose.COMMUNITY_IMAGE);

        assertThatThrownBy(() -> service.submit(UUID, request(List.of(doc("PASSPORT", "f1")))))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_FILE_INVALID");
    }

    // uq_fvd_file 위반으로 500이 되기 전에 잡는다.
    @Test
    @DisplayName("같은 파일을 여러 칸에 붙이면 거절한다")
    void rejects_duplicate_file() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2));
        givenFile("f1", 1L, 7L, FilePurpose.VERIFICATION_DOC);

        assertThatThrownBy(() -> service.submit(UUID,
                request(List.of(doc("PASSPORT", "f1"), doc("PASSPORT", "f1")))))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_FILE_INVALID");
    }

    // 조용히 무시하면 사용자는 낸 줄 알고 기다린다.
    @Test
    @DisplayName("요건에 없는 서류를 보내면 조용히 무시하지 않고 거절한다")
    void rejects_unrequested_document() {
        givenBasics();
        givenRequirements(req("PASSPORT", true, 2));

        assertThatThrownBy(() -> service.submit(UUID, request(List.of(doc("BANKBOOK", "f1")))))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_DOC_NOT_REQUIRED");
    }

    @Test
    @DisplayName("장수 상한을 넘으면 거절한다")
    void rejects_too_many_files() {
        givenBasics();
        givenRequirements(req("ENROLLMENT_CERT", true, 1));

        assertThatThrownBy(() -> service.submit(UUID, request(List.of(
                doc("ENROLLMENT_CERT", "f1"), doc("ENROLLMENT_CERT", "f2")))))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_DOC_TOO_MANY");
    }

    /*
     * ★ 요건 미구성 신분의 서류 0장 제출을 막는다 — 빈 요건 대비 빈 제출이 통과하면 PENDING 을
     *   거쳐 승인까지 간다. "계약 당사자의 신원은 반드시 사람이 검증한 문서로 확인된다"는
     *   제1 불변식이다(적대적 리뷰 2026-09-02). 거절을 빼면 죽는다.
     */
    @Test
    @DisplayName("요건이 비어 있으면 제출을 거절한다 — 서류 0장 승인 경로를 원천 차단")
    void empty_requirements_reject_submission() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
        given(residentStatusRepository.findByCodeAndActiveTrue("STUDENT"))
                .willReturn(Optional.of(withId(ResidentStatus.builder().code("STUDENT").build(), 1L)));
        given(countryRepository.findByCodeAndIsActiveTrue("VN"))
                .willReturn(Optional.of(withId(Country.builder().code("VN").nameEn("Vietnam").build(), 11L)));
        given(masterQueryService.findRequirements("STUDENT", "en")).willReturn(List.of());

        assertThatThrownBy(() -> service.submit(UUID, request(List.of())))
                .isInstanceOf(VerificationException.class)
                .extracting(e -> ((VerificationException) e).getErrorCode().getCode())
                .isEqualTo("VERIFICATION_REQUIREMENTS_NOT_READY");
        then(verificationRepository).should(never()).save(ArgumentMatchers.any());
    }

    /*
     * ★ 유저 행 잠금이 다른 어떤 조회보다 먼저다(FR-309 미러) — 연타 두 트랜잭션이 모두
     *   "심사 중 아님" 검사를 통과해 PENDING 2행이 생기고, INV-02 가 승인된 첫 행 대신 유령
     *   두 번째 행을 집었다(적대적 리뷰 2026-09-02). 잠금을 뒤로 돌리면 죽는다.
     */
    @Test
    @DisplayName("유저 잠금이 제출의 첫 읽기다 — 연타가 PENDING 2행을 만들면 안 된다")
    void user_lock_is_the_first_read_on_submit() {
        given(userQueryService.getActiveUser(UUID)).willReturn(withId(
                User.builder().uuid(UUID).status(UserStatus.ACTIVE).role(UserRole.USER).build(), 7L));
        given(residentStatusRepository.findByCodeAndActiveTrue("STUDENT"))
                .willReturn(Optional.of(withId(ResidentStatus.builder().code("STUDENT").build(), 1L)));
        given(countryRepository.findByCodeAndIsActiveTrue("VN"))
                .willReturn(Optional.of(withId(Country.builder().code("VN").nameEn("Vietnam").build(), 11L)));
        given(masterQueryService.findRequirements("STUDENT", "en")).willReturn(List.of());

        assertThatThrownBy(() -> service.submit(UUID, request(List.of())))
                .isInstanceOf(VerificationException.class);

        InOrder inOrder = Mockito.inOrder(userRepository, userQueryService);
        inOrder.verify(userRepository).findIdByUuidForUpdate(UUID);
        inOrder.verify(userQueryService).getActiveUser(UUID);
    }
}
