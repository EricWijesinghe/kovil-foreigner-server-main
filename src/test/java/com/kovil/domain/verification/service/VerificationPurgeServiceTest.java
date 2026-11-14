package com.kovil.domain.verification.service;

import com.kovil.domain.file.service.FileDeletionService;
import com.kovil.domain.verification.entity.ForeignerVerification;
import com.kovil.domain.verification.entity.ForeignerVerificationDocument;
import com.kovil.domain.verification.repository.ForeignerVerificationDocumentRepository;
import com.kovil.domain.verification.repository.ForeignerVerificationRepository;
import com.kovil.domain.verification.enums.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * ★ 신분 증빙 파기 시점은 **심사 완료가 아니라 탈퇴**다(PO 확정 2026-09-23).
 *   임대는 계약이고, 계약 기간 중 문제가 생기면 "이 사람이 누구였는지"를 증명할 근거가 필요하다.
 *   심사가 끝났다고 바로 버리면 그 근거가 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class VerificationPurgeServiceTest {

    @Mock ForeignerVerificationRepository verificationRepository;
    @Mock ForeignerVerificationDocumentRepository documentRepository;
    @Mock FileDeletionService fileDeletionService;

    // 고정 시계로 만든다 — 목 Clock 은 instant() 가 null 이라 반려 시각 계산에서 터진다.
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private VerificationPurgeService service;

    // ★ 필드 초기화로 만들면 목 주입보다 먼저 돌아 리포지토리가 전부 null 이다.
    @BeforeEach
    void setUp() {
        service = new VerificationPurgeService(
                verificationRepository, documentRepository, fileDeletionService,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private static ForeignerVerification verification(Long id) {
        ForeignerVerification v = ForeignerVerification.builder().build();
        try {
            var f = ForeignerVerification.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(v, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return v;
    }

    private static ForeignerVerificationDocument document(Long fileId) {
        return ForeignerVerificationDocument.builder().fileId(fileId).build();
    }

    /*
     * 재제출은 인증 행을 새로 만든다 — 최신 건만 훑으면 **옛 제출물이 영원히 남는다.**
     * 반려당하고 다시 낸 사람의 첫 여권 사진이 그대로 있는 상태가 된다.
     */
    @Test
    @DisplayName("재제출로 쌓인 옛 인증 건의 서류까지 전부 버린다")
    void purges_documents_of_every_verification() {
        given(verificationRepository.findByUserIdOrderByIdDesc(7L))
                .willReturn(List.of(verification(30L), verification(20L), verification(10L)));
        given(documentRepository.findByVerificationIdIn(List.of(30L, 20L, 10L)))
                .willReturn(List.of(document(100L), document(101L), document(102L)));

        service.purgeOnWithdrawal(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        then(fileDeletionService).should().discardByIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(100L, 101L, 102L);
    }

    @Test
    @DisplayName("인증을 낸 적 없으면 아무것도 하지 않는다")
    void no_verification_is_noop() {
        given(verificationRepository.findByUserIdOrderByIdDesc(8L)).willReturn(List.of());

        service.purgeOnWithdrawal(8L);

        then(documentRepository).should(never()).findByVerificationIdIn(ArgumentMatchers.any());
        then(fileDeletionService).should(never()).discardByIds(ArgumentMatchers.any());
    }

    /*
     * 심사 대기 건을 닫지 않으면 **서류가 한 장도 없는 건이 대기열에 남고 승인 버튼이 살아 있다.**
     * 바로 아래에서 서류를 파기 표시하므로, 닫는 일과 버리는 일은 한 곳에 있어야 한다.
     */
    @Test
    @DisplayName("심사 대기 중이던 인증은 '회원이 탈퇴했습니다.' 사유로 반려된다")
    void rejects_pending_verification_on_withdrawal() {
        ForeignerVerification pending = verification(11L);
        given(verificationRepository.findByUserIdOrderByIdDesc(7L)).willReturn(List.of(pending));
        given(documentRepository.findByVerificationIdIn(List.of(11L))).willReturn(List.of());

        service.purgeOnWithdrawal(7L);

        assertThat(pending.getStatus())
                .isEqualTo(VerificationStatus.REJECTED);
        assertThat(pending.getRejectReason()).isEqualTo("회원이 탈퇴했습니다.");
        // 사람이 판단한 반려가 아니다 — 심사자 자리를 채우면 감사 기록이 거짓이 된다.
        assertThat(pending.getReviewedBy()).isNull();
    }

    // 이미 심사가 끝난 건을 다시 건드리면 **누가 언제 승인했는지가 덮어씌워진다.**
    @Test
    @DisplayName("이미 승인된 인증의 판정은 탈퇴가 건드리지 않는다")
    void keeps_decided_verification_untouched() {
        ForeignerVerification approved = verification(12L);
        approved.approve(99L, LocalDate.of(2027, 1, 1),
                LocalDateTime.of(2026, 1, 1, 0, 0));
        given(verificationRepository.findByUserIdOrderByIdDesc(7L)).willReturn(List.of(approved));
        given(documentRepository.findByVerificationIdIn(List.of(12L))).willReturn(List.of());

        service.purgeOnWithdrawal(7L);

        assertThat(approved.getStatus())
                .isEqualTo(VerificationStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo(99L);
    }
}
