package com.kovil.domain.rentalrequest.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.dto.response.AdminRentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.service.RentalRequestClaimHistory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/*
 * 잡기 이력 — 남기고 읽기를 진짜 JPA 로 고정한다(서비스 테스트는 저장소를 목킹해 매핑 · 정렬 · 칼럼을 보지 못한다).
 *   · 사건 넷이 모양대로 저장된다(원인은 대기 복귀에만, 결과는 종료에만, 행위자는 대기 복귀에 없다).
 *   · 한 요청의 이력은 일어난 순서(id)대로, 다른 요청의 이력은 섞이지 않는다.
 *   · 상호는 저장하지 않고 그 사무소의 지금 상호를 읽는다.
 *   · 한 번 남긴 행은 고쳐지지 않는다(칼럼마다 updatable = false).
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, RentalRequestClaimHistory.class})
class RentalRequestClaimEventRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 8, 9, 0);
    private static final long PARTNER_A = 7L;
    private static final long PARTNER_B = 8L;

    @Autowired RentalRequestClaimHistory history;
    @Autowired RentalRequestClaimEventRepository repository;
    @Autowired RentalRequestRepository requestRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired EntityManager entityManager;

    private BrokerageOffice office(String uuid, String name) {
        return officeRepository.saveAndFlush(BrokerageOffice.builder().uuid(uuid).regNo("99999-2026-00001").name(name)
                .ceoName("홍길동").address("서울특별시 광진구 능동로 000").tel("02-000-0000").build());
    }

    private RentalRequest request(String number) {
        return requestRepository.saveAndFlush(RentalRequest.builder().requestNumber(number).userId(10L)
                .verificationId(99L).status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1))
                .build());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("잡기 · 놓기 · 대기 복귀 · 종료가 모양대로 저장되고, 한 요청의 이력은 일어난 순서대로 나온다")
    void stores_each_event_and_reads_in_order() {
        BrokerageOffice a = office("o-a", "행복공인중개사사무소");
        BrokerageOffice b = office("o-b", "소망공인중개사사무소");
        RentalRequest request = request("RQ-261008-AAAAA1");
        RentalRequest other = request("RQ-261008-AAAAA2");

        history.claimed(request, a.getId(), PARTNER_A, AT);
        history.released(request, a.getId(), PARTNER_A, AT.plusMinutes(5));
        history.claimed(other, a.getId(), PARTNER_A, AT.plusMinutes(6));
        history.claimed(request, b.getId(), PARTNER_B, AT.plusMinutes(10));
        history.returned(request, b.getId(), RentalRequestReturnCause.DIVISION_SUSPENDED, AT.plusMinutes(20));
        history.claimed(request, a.getId(), PARTNER_A, AT.plusMinutes(30));
        history.closed(request, a.getId(), PARTNER_A, RentalRequestOutcome.FOUND, AT.plusMinutes(40));
        flushAndClear();

        assertThat(repository.findByRentalRequestIdOrderByIdAsc(request.getId()))
                .extracting(RentalRequestClaimEvent::getOfficeId, RentalRequestClaimEvent::getActorId,
                        RentalRequestClaimEvent::getEventType, RentalRequestClaimEvent::getReturnCause,
                        RentalRequestClaimEvent::getOutcome, RentalRequestClaimEvent::getOccurredAt)
                .containsExactly(
                        tuple(a.getId(), PARTNER_A, RentalRequestClaimEventType.CLAIMED, null, null, AT),
                        tuple(a.getId(), PARTNER_A, RentalRequestClaimEventType.RELEASED, null, null, AT.plusMinutes(5)),
                        tuple(b.getId(), PARTNER_B, RentalRequestClaimEventType.CLAIMED, null, null, AT.plusMinutes(10)),
                        tuple(b.getId(), null, RentalRequestClaimEventType.RETURNED,
                                RentalRequestReturnCause.DIVISION_SUSPENDED, null, AT.plusMinutes(20)),
                        tuple(a.getId(), PARTNER_A, RentalRequestClaimEventType.CLAIMED, null, null, AT.plusMinutes(30)),
                        tuple(a.getId(), PARTNER_A, RentalRequestClaimEventType.CLOSED, null,
                                RentalRequestOutcome.FOUND, AT.plusMinutes(40)));
        assertThat(repository.findByRentalRequestIdOrderByIdAsc(other.getId()))
                .extracting(RentalRequestClaimEvent::getEventType)
                .containsExactly(RentalRequestClaimEventType.CLAIMED);
    }

    @Test
    @DisplayName("관리자 상세의 이력은 사무소 uuid · 지금 상호 · 원인 · 결과를 싣는다 — 잡힌 적이 없으면 빈 목록")
    void admin_view_reads_current_office_name() {
        BrokerageOffice a = office("o-a", "행복공인중개사사무소");
        RentalRequest request = request("RQ-261008-BBBBB1");
        history.claimed(request, a.getId(), PARTNER_A, AT);
        history.returned(request, a.getId(), RentalRequestReturnCause.REPRESENTATIVE_WITHDRAWN, AT.plusHours(1));
        ReflectionTestUtils.setField(a, "name", "새상호공인중개사사무소");
        officeRepository.saveAndFlush(a);
        flushAndClear();

        List<AdminRentalRequestResponse.ClaimEvent> view = history.historyOf(request.getId());

        assertThat(view).extracting(AdminRentalRequestResponse.ClaimEvent::type,
                        AdminRentalRequestResponse.ClaimEvent::officeUuid, AdminRentalRequestResponse.ClaimEvent::officeName,
                        AdminRentalRequestResponse.ClaimEvent::occurredAt, AdminRentalRequestResponse.ClaimEvent::returnCause,
                        AdminRentalRequestResponse.ClaimEvent::outcome)
                .containsExactly(
                        tuple("CLAIMED", "o-a", "새상호공인중개사사무소", AT, null, null),
                        tuple("RETURNED", "o-a", "새상호공인중개사사무소", AT.plusHours(1), "REPRESENTATIVE_WITHDRAWN", null));
        assertThat(history.historyOf(request("RQ-261008-BBBBB2").getId())).isEmpty();
    }

    // append-only — 엔티티를 고쳐 flush 해도 UPDATE 가 나가지 않는다(칼럼마다 updatable = false).
    @Test
    @DisplayName("남긴 행은 고쳐지지 않는다 — 엔티티를 바꿔도 DB 의 값은 그대로다")
    void rows_are_not_updatable() {
        BrokerageOffice a = office("o-a", "행복공인중개사사무소");
        RentalRequest request = request("RQ-261008-CCCCC1");
        history.claimed(request, a.getId(), PARTNER_A, AT);
        flushAndClear();
        RentalRequestClaimEvent saved = repository.findByRentalRequestIdOrderByIdAsc(request.getId()).get(0);

        ReflectionTestUtils.setField(saved, "eventType", RentalRequestClaimEventType.RELEASED);
        ReflectionTestUtils.setField(saved, "occurredAt", AT.plusDays(1));
        ReflectionTestUtils.setField(saved, "actorId", 999L);
        flushAndClear();

        RentalRequestClaimEvent reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEventType()).isEqualTo(RentalRequestClaimEventType.CLAIMED);
        assertThat(reloaded.getOccurredAt()).isEqualTo(AT);
        assertThat(reloaded.getActorId()).isEqualTo(PARTNER_A);
    }

    // 모양은 생성 메서드가 지킨다 — 행위자 없는 잡기 · 원인 없는 대기 복귀 · 결과 없는 종료는 만들 수 없다.
    @Test
    @DisplayName("사건마다 필요한 값이 없으면 만들 수 없다")
    void factories_require_their_fields() {
        assertThatThrownBy(() -> RentalRequestClaimEvent.claimed(1L, 2L, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RentalRequestClaimEvent.released(1L, 2L, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RentalRequestClaimEvent.returned(1L, 2L, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RentalRequestClaimEvent.closed(1L, 2L, 3L, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RentalRequestClaimEvent.claimed(1L, null, 3L, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RentalRequestClaimEvent.claimed(1L, 2L, 3L, null)).isInstanceOf(NullPointerException.class);
    }

    /*
     * 유학생 응답의 '대기 복귀' 시각 — 놓기 · 대기 복귀만 세고(잡기 · 종료는 아니다), 요청마다 그중 마지막 시각.
     * 마지막 사건이 종료라도 그보다 앞선 대기 복귀 시각이 나온다. 놓인 적 없는 요청은 행이 없다.
     */
    @Test
    @DisplayName("요청마다 놓기 · 대기 복귀 중 마지막 시각 — 잡기 · 종료는 세지 않고, 놓인 적 없는 요청은 빠진다")
    void last_releases_per_request() {
        BrokerageOffice a = office("o-a", "행복공인중개사사무소");
        RentalRequest released = request("RQ-261013-AAAAA1");
        RentalRequest returnedThenClosed = request("RQ-261013-AAAAA2");
        RentalRequest neverReleased = request("RQ-261013-AAAAA3");
        RentalRequest notAsked = request("RQ-261013-AAAAA4");

        history.claimed(released, a.getId(), PARTNER_A, AT);
        history.released(released, a.getId(), PARTNER_A, AT.plusMinutes(5));
        history.claimed(released, a.getId(), PARTNER_A, AT.plusMinutes(6));
        history.released(released, a.getId(), PARTNER_A, AT.plusMinutes(9));
        history.claimed(returnedThenClosed, a.getId(), PARTNER_A, AT);
        history.returned(returnedThenClosed, a.getId(), RentalRequestReturnCause.ACCOUNT_SUSPENDED, AT.plusMinutes(7));
        history.claimed(returnedThenClosed, a.getId(), PARTNER_B, AT.plusMinutes(8));
        history.closed(returnedThenClosed, a.getId(), PARTNER_B, RentalRequestOutcome.FOUND, AT.plusMinutes(30));
        history.claimed(neverReleased, a.getId(), PARTNER_A, AT);
        history.released(notAsked, a.getId(), PARTNER_A, AT.plusMinutes(1));
        flushAndClear();

        assertThat(repository.findLastReleases(
                List.of(released.getId(), returnedThenClosed.getId(), neverReleased.getId()),
                List.of(RentalRequestClaimEventType.RELEASED, RentalRequestClaimEventType.RETURNED)))
                .extracting(RentalRequestLastRelease::requestId, RentalRequestLastRelease::releasedAt)
                .containsExactlyInAnyOrder(
                        tuple(released.getId(), AT.plusMinutes(9)),
                        tuple(returnedThenClosed.getId(), AT.plusMinutes(7)));
    }
}
