package com.kovil.domain.listing.service;

import com.kovil.domain.listing.entity.Listing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/*
 * ★★ 배치의 불변식은 하나다 — **한 건이 터져도 나머지가 돈다.**
 *    전건을 한 트랜잭션에 묶으면 매물 하나의 데이터 문제로 그날 만료가 통째로 멈추고,
 *    다음 날도 같은 건에서 또 멈춘다. 광고는 그 사이 계속 떠 있는다.
 */
class ListingExpirySchedulerTest {

    private ListingExpiryProcessor processor;
    private ListingExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        processor = mock(ListingExpiryProcessor.class);
        scheduler = new ListingExpiryScheduler(processor);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 처리한다")
    void one_failure_does_not_stop_the_rest() {
        // ★ 목 안에서 목을 세우면 Mockito 가 미완성 스터빙으로 읽는다 — 먼저 만들고 넘긴다.
        List<Listing> targets = List.of(ref(1L), ref(2L), ref(3L));
        given(processor.findExpirable()).willReturn(targets);
        given(processor.findArchivable()).willReturn(List.of());
        willThrow(new IllegalStateException("boom")).given(processor).expire(2L);

        scheduler.run();

        verify(processor).expire(1L);
        verify(processor).expire(2L);
        verify(processor).expire(3L);
    }

    // 만료와 자동 종료는 **스캔 축이 다르다**(상태·기준 시각). 한쪽만 돌면 나머지가 쌓인다.
    @Test
    @DisplayName("한 회차에서 만료와 자동 종료를 모두 돈다")
    void runs_both_passes() {
        List<Listing> expirable = List.of(ref(1L));
        List<Listing> archivable = List.of(ref(9L));
        given(processor.findExpirable()).willReturn(expirable);
        given(processor.findArchivable()).willReturn(archivable);

        scheduler.run();

        verify(processor).expire(1L);
        verify(processor).archive(9L);
    }

    @Test
    @DisplayName("대상이 없으면 아무것도 하지 않는다")
    void empty_is_a_no_op() {
        given(processor.findExpirable()).willReturn(List.of());
        given(processor.findArchivable()).willReturn(List.of());

        scheduler.run();

        verify(processor, never()).expire(anyLong());
        verify(processor, never()).archive(anyLong());
    }

    // 스케줄러는 id 만 쓴다 — 엔티티 전체를 세울 필요가 없다.
    private Listing ref(Long id) {
        Listing listing = mock(Listing.class);
        when(listing.getId()).thenReturn(id);
        return listing;
    }
}
