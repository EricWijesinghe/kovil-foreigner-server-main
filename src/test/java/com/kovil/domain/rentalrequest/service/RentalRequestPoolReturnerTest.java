package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/*
 * 맡을 사람이 사라진 사무소의 진행 중 요청을 대기로 되돌린다(대표자 탈퇴 · 부문 정지 · 대표자 계정 정지).
 *   · 진행 중인 것만, 전부 — 사무소 · 잡은 시각을 비우고 유학생마다 "다시 대기" 알림(처리 시각이 중복 키에 들어간다).
 *   · 요청마다 잡기 이력에 대기 복귀 한 행 — 원인(호출자가 준다 · 계정 정지 길은 계정 정지), 그 사무소, 행위자 없음.
 *   · 대표자 계정 정지는 대표자 연결을 잠금 읽기로 찾고 **사무소 행을 잠근 뒤에** 요청을 읽는다. 대표자가 아니면 아무것도 하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class RentalRequestPoolReturnerTest {

    private static final long OFFICE = 70L;
    private static final long PARTNER = 7L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 12, 0);

    @Mock RentalRequestRepository requestRepository;
    @Mock NotificationPublisher notificationPublisher;
    @Mock BrokerProfileRepository profileRepository;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock RentalRequestClaimEventRepository eventRepository;

    private RentalRequestPoolReturner returner;

    @BeforeEach
    void setUp() {
        returner = new RentalRequestPoolReturner(requestRepository, new RentalRequestNotifier(notificationPublisher),
                new RentalRequestClaimHistory(eventRepository, officeRepository), profileRepository, officeRepository);
    }

    private List<RentalRequestClaimEvent> historyRows() {
        ArgumentCaptor<RentalRequestClaimEvent> saved = ArgumentCaptor.forClass(RentalRequestClaimEvent.class);
        then(eventRepository).should(Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private static RentalRequest held(String uuid, long studentId) {
        RentalRequest request = RentalRequest.builder()
                .uuid(uuid).requestNumber("RQ-" + uuid).userId(studentId).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 11, 1)).build();
        ReflectionTestUtils.setField(request, "id", 500L + studentId);
        request.claim(OFFICE, AT.minusDays(1));
        return request;
    }

    @Test
    @DisplayName("그 사무소가 쥔 진행 중 요청을 전부 대기로 돌리고 유학생마다 다시 대기 알림을 넣는다")
    void returns_all_held_requests_and_notifies() {
        RentalRequest first = held("r-1", 10L);
        RentalRequest second = held("r-2", 11L);
        given(requestRepository.findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS))
                .willReturn(List.of(first, second));

        int returned = returner.returnHeldBy(OFFICE, RentalRequestReturnCause.DIVISION_SUSPENDED, AT);

        assertThat(returned).isEqualTo(2);
        for (RentalRequest request : List.of(first, second)) {
            assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
            assertThat(request.getOfficeId()).isNull();
            assertThat(request.getClaimedAt()).isNull();
            then(notificationPublisher).should().publish(request.getUserId(), NotiType.RENTAL_REQUEST_RELEASED,
                    "RENTAL_REQUEST_RELEASED:request:" + request.getUuid() + ":" + AT,
                    Map.of("0", request.getRequestNumber()), "/rental-requests/" + request.getUuid());
        }
    }

    // 원인마다 요청 하나에 대기 복귀 한 행 — 그 사무소, 행위자 없음(사람이 한 일이 아니다), 결과 없음, 처리 시각.
    @ParameterizedTest(name = "{0}")
    @EnumSource(RentalRequestReturnCause.class)
    @DisplayName("되돌린 요청마다 잡기 이력에 대기 복귀(원인) 한 행을 남긴다")
    void records_one_return_row_per_request(RentalRequestReturnCause cause) {
        RentalRequest first = held("r-1", 10L);
        RentalRequest second = held("r-2", 11L);
        given(requestRepository.findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS))
                .willReturn(List.of(first, second));

        returner.returnHeldBy(OFFICE, cause, AT);

        assertThat(historyRows())
                .extracting(RentalRequestClaimEvent::getRentalRequestId, RentalRequestClaimEvent::getOfficeId,
                        RentalRequestClaimEvent::getActorId, RentalRequestClaimEvent::getEventType,
                        RentalRequestClaimEvent::getReturnCause, RentalRequestClaimEvent::getOutcome,
                        RentalRequestClaimEvent::getOccurredAt)
                .containsExactly(
                        tuple(510L, OFFICE, null, RentalRequestClaimEventType.RETURNED, cause, null, AT),
                        tuple(511L, OFFICE, null, RentalRequestClaimEventType.RETURNED, cause, null, AT));
    }

    @Test
    @DisplayName("쥔 요청이 없으면 아무 알림도 이력도 없다")
    void nothing_held() {
        given(requestRepository.findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS)).willReturn(List.of());

        assertThat(returner.returnHeldBy(OFFICE, RentalRequestReturnCause.REPRESENTATIVE_WITHDRAWN, AT)).isZero();
        then(notificationPublisher).should(never()).publish(any(), any(), anyString(), anyMap(), anyString());
        then(eventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("대표자 계정 정지 — 대표자 연결(잠금 읽기) → 사무소 잠금 → 요청 읽기 순서로 되돌린다")
    void representative_locks_office_before_reading_requests() {
        RentalRequest request = held("r-1", 10L);
        given(profileRepository.findOfficeIdForUpdate(PARTNER)).willReturn(Optional.of(OFFICE));
        given(requestRepository.findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS))
                .willReturn(List.of(request));

        assertThat(returner.returnHeldByRepresentative(PARTNER, AT)).isEqualTo(1);

        InOrder order = inOrder(profileRepository, officeRepository, requestRepository);
        order.verify(profileRepository).findOfficeIdForUpdate(PARTNER);
        order.verify(officeRepository).lockById(OFFICE);
        order.verify(requestRepository).findByOfficeIdAndStatus(OFFICE, RentalRequestStatus.IN_PROGRESS);
        assertThat(request.getStatus()).isEqualTo(RentalRequestStatus.SUBMITTED);
        then(profileRepository).should(never()).findByUserId(any());
        assertThat(historyRows()).extracting(RentalRequestClaimEvent::getReturnCause)
                .as("이 길은 계정 정지만 부른다").containsExactly(RentalRequestReturnCause.ACCOUNT_SUSPENDED);
    }

    @Test
    @DisplayName("대표자가 아니면(대표자 연결 없음) 사무소도 요청도 건드리지 않는다")
    void not_a_representative() {
        given(profileRepository.findOfficeIdForUpdate(PARTNER)).willReturn(Optional.empty());

        assertThat(returner.returnHeldByRepresentative(PARTNER, AT)).isZero();
        then(officeRepository).shouldHaveNoInteractions();
        then(requestRepository).shouldHaveNoInteractions();
        then(eventRepository).shouldHaveNoInteractions();
    }
}
