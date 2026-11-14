package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/*
 * 유학생에게 가는 임대 요청 알림 셋(잡힘 · 종료 · 다시 대기).
 * ★ 같은 요청이 잡히고 · 놓이고 · 다시 잡힐 수 있다 — 중복 키에 처리 시각이 들어가야 두 번째 잡힘이 버려지지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class RentalRequestNotifierTest {

    private static final Long STUDENT = 10L;
    private static final String UUID = "r-uuid";
    private static final LocalDateTime FIRST = LocalDateTime.of(2026, 10, 7, 10, 0);
    private static final LocalDateTime SECOND = FIRST.plusHours(3);

    @Mock NotificationPublisher publisher;

    private RentalRequestNotifier notifier() {
        return new RentalRequestNotifier(publisher);
    }

    private static RentalRequest request() {
        return RentalRequest.builder()
                .uuid(UUID).requestNumber("RQ-261007-K3M9PQ").userId(STUDENT).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).build();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> paramsCaptor() {
        return ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    @DisplayName("잡힘 — 유학생에게 요청 번호 · 사무소 상호를 싣고 요청 상세로 보낸다")
    void claimed_carries_number_and_office_name() {
        RentalRequest request = request();
        request.claim(7L, FIRST);
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();

        notifier().claimed(request, "행복공인중개사사무소");

        then(publisher).should().publish(eq(STUDENT), eq(NotiType.RENTAL_REQUEST_IN_PROGRESS),
                eq("RENTAL_REQUEST_IN_PROGRESS:request:" + UUID + ":" + FIRST), params.capture(),
                eq("/rental-requests/" + UUID));
        assertThat(params.getValue()).containsExactlyInAnyOrderEntriesOf(
                Map.of("0", "RQ-261007-K3M9PQ", "1", "행복공인중개사사무소"));
    }

    @Test
    @DisplayName("잡히고 놓이고 다시 잡히면 알림 셋이 각자 다른 중복 키로 나간다")
    void each_claim_and_release_has_its_own_key() {
        RentalRequest request = request();
        request.claim(7L, FIRST);
        notifier().claimed(request, "행복공인중개사사무소");
        request.release(7L);
        notifier().released(request, FIRST.plusHours(1));
        request.claim(8L, SECOND);
        notifier().claimed(request, "테스트공인중개사사무소");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        then(publisher).should(times(3)).publish(eq(STUDENT), any(), keys.capture(), anyMap(), anyString());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates().containsExactly(
                "RENTAL_REQUEST_IN_PROGRESS:request:" + UUID + ":" + FIRST,
                "RENTAL_REQUEST_RELEASED:request:" + UUID + ":" + FIRST.plusHours(1),
                "RENTAL_REQUEST_IN_PROGRESS:request:" + UUID + ":" + SECOND);
    }

    // 결과(방을 구했는지)는 싣지 않는다 — 유학생 화면은 "종료"만 그린다.
    @Test
    @DisplayName("종료 — 요청 번호만 싣고 종료 시각을 중복 키에 넣는다")
    void closed_carries_number_only() {
        RentalRequest request = request();
        request.claim(7L, FIRST);
        request.close(7L, RentalRequestOutcome.FOUND, SECOND);
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();

        notifier().closed(request);

        then(publisher).should().publish(eq(STUDENT), eq(NotiType.RENTAL_REQUEST_CLOSED),
                eq("RENTAL_REQUEST_CLOSED:request:" + UUID + ":" + SECOND), params.capture(),
                eq("/rental-requests/" + UUID));
        assertThat(params.getValue()).containsExactlyEntriesOf(Map.of("0", "RQ-261007-K3M9PQ"));
    }

    @Test
    @DisplayName("다시 대기 — 요청 번호만 싣는다(놓기 · 맡을 사람이 사라짐 공통)")
    void released_carries_number_only() {
        RentalRequest request = request();
        request.claim(7L, FIRST);
        request.returnToPool();
        ArgumentCaptor<Map<String, Object>> params = paramsCaptor();

        notifier().released(request, SECOND);

        then(publisher).should().publish(eq(STUDENT), eq(NotiType.RENTAL_REQUEST_RELEASED),
                eq("RENTAL_REQUEST_RELEASED:request:" + UUID + ":" + SECOND), params.capture(),
                eq("/rental-requests/" + UUID));
        assertThat(params.getValue()).containsExactlyEntriesOf(Map.of("0", "RQ-261007-K3M9PQ"));
    }

    // 처리 시각 없이 키를 만들면 같은 요청의 다음 알림이 "이미 보냄"으로 버려진다 — 조용히 보내지 말고 터뜨린다.
    @Test
    @DisplayName("처리 시각이 없으면 보내지 않고 터진다")
    void missing_time_fails_loudly() {
        assertThatThrownBy(() -> notifier().released(request(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> notifier().claimed(request(), "행복공인중개사사무소"))
                .isInstanceOf(NullPointerException.class);
        then(publisher).should(never()).publish(any(), any(), anyString(), anyMap(), anyString());
    }

    // 중복 키 칸은 150자다 — 가장 긴 종류 · uuid(36자) · 마이크로초 시각을 붙여도 넘지 않아야 한다.
    @Test
    @DisplayName("가장 긴 중복 키도 칸(150자)에 들어간다")
    void dedupe_key_fits_the_column() {
        RentalRequest request = RentalRequest.builder()
                .uuid("00000000-0000-0000-0000-000000000000").requestNumber("RQ-261007-K3M9PQ")
                .userId(STUDENT).verificationId(99L).status(RentalRequestStatus.SUBMITTED).build();
        request.claim(7L, LocalDateTime.of(2026, 10, 7, 10, 0, 0, 123_456_000));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        notifier().claimed(request, "행복공인중개사사무소");

        then(publisher).should().publish(any(), any(), key.capture(), anyMap(), anyString());
        assertThat(key.getValue()).hasSizeLessThanOrEqualTo(150);
    }
}
