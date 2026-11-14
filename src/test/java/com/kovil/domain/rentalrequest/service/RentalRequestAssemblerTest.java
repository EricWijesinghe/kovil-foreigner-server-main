package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.entity.Region;
import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestLastRelease;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.BDDMockito;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RentalRequestAssemblerTest {

    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Mock RegionRepository regionRepository;
    @Mock RequestOptionRepository requestOptionRepository;
    @Mock RentalRequestRegionRepository requestRegionRepository;
    @Mock RentalRequestOptionRepository requestOptionLinkRepository;
    @Mock RequestOptionQueryService optionQueryService;
    @Mock ClaimedOfficeResolver claimedOfficeResolver;
    @Mock RentalRequestClaimEventRepository claimEventRepository;

    private RentalRequestAssembler assembler() {
        return new RentalRequestAssembler(regionRepository, requestOptionRepository,
                requestRegionRepository, requestOptionLinkRepository, optionQueryService, claimedOfficeResolver,
                claimEventRepository);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
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

    private static RentalRequest request() {
        return withId(RentalRequest.builder()
                .uuid("r-uuid").requestNumber("RQ-260811-ABC123").userId(10L).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).moveInDate(LocalDate.of(2026, 10, 1))
                .build(), 500L);
    }

    private static RentalRequestResponse.Office office(String ceoPhone) {
        return new RentalRequestResponse.Office("행복공인중개사사무소", "홍길동", "서울특별시 광진구 능동로 000",
                "02-000-0000", ceoPhone);
    }

    // 맡은 사무소 칸은 사무소 해석기가 채운다 — 여기서는 요청 순서대로 넘겨받은 것을 그 자리에 싣는지 본다.
    private void givenOffices(RentalRequestResponse.Office... offices) {
        given(claimedOfficeResolver.officesOf(any())).willReturn(Arrays.asList(offices));
    }

    private void givenLinks() {
        given(requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of(
                RentalRequestRegion.builder().rentalRequestId(500L).regionId(20L).build()));
        given(requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of(
                RentalRequestOption.builder().rentalRequestId(500L).requestOptionId(11L).build()));
        given(regionRepository.findByIdIn(any())).willReturn(List.of(
                withId(Region.builder().parentId(1L).code("11215").nameKo("광진구").nameEn("Gwangjin-gu").build(), 20L)));
        given(requestOptionRepository.findByIdIn(any()))
                .willReturn(List.of(withId(RequestOption.builder().parentId(1L).code("ONE_ROOM").build(), 11L)))
                .willReturn(List.of(withId(RequestOption.builder().code("ROOM_TYPE").selectionType("SINGLE").build(), 1L)));
        given(optionQueryService.resolveNames(anyString())).willReturn(Map.<Long, RequestOptionTranslation>of());
        given(optionQueryService.nameOf(any(), any())).willReturn("원룸");
    }

    /*
     * ★ 종료 결과(방을 구했는지)는 성사율을 남기는 내부 기록이다 — 유학생 화면은 "종료"만 그린다.
     *   직렬화 결과 전체를 검사한다(필드 하나를 빼먹고 넣어도 여기서 깨진다).
     */
    @Test
    @DisplayName("응답 어디에도 종료 결과(outcome)가 없다 — 사무소가 종료한 요청이어도")
    void outcome_never_leaks() {
        givenLinks();
        RentalRequest closed = request();
        closed.claim(7L, CLAIMED_AT);
        closed.close(7L, RentalRequestOutcome.NOT_PROCEEDED, CLAIMED_AT.plusDays(3));
        givenOffices(office(null));

        RentalRequestResponse response = assembler().toResponse(closed);
        String json = JsonMapper.builder().build().writeValueAsString(response);

        assertThat(json).doesNotContain("outcome").doesNotContain("NOT_PROCEEDED").doesNotContain("FOUND");
        assertThat(json).doesNotContain("assignedManager").doesNotContain("managerMemo");
        assertThat(json).contains("RQ-260811-ABC123").contains("\"status\":\"CLOSED\"");
        assertThat(response.editable()).isFalse();
    }

    @Test
    @DisplayName("editable이 상태에서 계산된다 — FE가 전이 규칙을 알 필요가 없다")
    void editable_is_derived() {
        givenLinks();
        givenOffices((RentalRequestResponse.Office) null);

        assertThat(assembler().toResponse(request()).editable()).isTrue();
    }

    @Test
    @DisplayName("사무소가 맡은 요청은 맡은 사무소 · 잡은 시각이 실리고, 진행 중이라 아직 고칠 수 있다")
    void in_progress_carries_office_and_claimed_at() {
        givenLinks();
        RentalRequest inProgress = request();
        inProgress.claim(7L, CLAIMED_AT);
        givenOffices(office("010-0000-1234"));

        RentalRequestResponse response = assembler().toResponse(inProgress);

        assertThat(response.status()).isEqualTo("IN_PROGRESS");
        assertThat(response.claimedAt()).isEqualTo(CLAIMED_AT);
        assertThat(response.office()).isEqualTo(office("010-0000-1234"));
        assertThat(response.editable()).isTrue();
    }

    @Test
    @DisplayName("대기 요청은 맡은 사무소 · 잡은 시각이 비어 있다")
    void submitted_has_no_office() {
        givenLinks();
        givenOffices((RentalRequestResponse.Office) null);

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.office()).isNull();
        assertThat(response.claimedAt()).isNull();
    }

    // 목록은 페이지 단위로 한 번에 해석한다 — 행마다 해석기를 부르면 사무소 · 대표자 · 계정을 행 수만큼 다시 읽는다.
    @Test
    @DisplayName("목록의 각 요청은 자기 자리의 사무소를 받는다 — 해석은 한 번에")
    void offices_follow_request_order() {
        given(requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of());
        given(requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of());
        given(regionRepository.findByIdIn(any())).willReturn(List.of());
        given(requestOptionRepository.findByIdIn(any())).willReturn(List.of());
        given(optionQueryService.resolveNames(anyString())).willReturn(Map.<Long, RequestOptionTranslation>of());
        RentalRequest waiting = request();
        RentalRequest taken = withId(RentalRequest.builder()
                .uuid("r-2").requestNumber("RQ-260811-XYZ789").userId(10L).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).build(), 501L);
        taken.claim(7L, CLAIMED_AT);
        givenOffices(null, office("010-0000-1234"));

        List<RentalRequestResponse> responses = assembler().toResponses(List.of(waiting, taken));

        assertThat(responses).extracting(RentalRequestResponse::office)
                .containsExactly(null, office("010-0000-1234"));
        BDDMockito.then(claimedOfficeResolver).should(Mockito.times(1)).officesOf(any());
    }

    @Test
    @DisplayName("한국어 요청은 국문 지역명, 그 외 언어는 영문이다")
    void region_name_follows_locale() {
        givenLinks();
        givenOffices((RentalRequestResponse.Office) null);
        LocaleContextHolder.setLocale(Locale.KOREAN);

        assertThat(assembler().toResponse(request()).regions())
                .extracting(RentalRequestResponse.Region::name).containsExactly("광진구");
    }

    @Test
    @DisplayName("선택이 하나도 없어도 빈 배열로 내려간다")
    void empty_selections_are_empty_lists() {
        given(requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of());
        given(requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of());
        given(regionRepository.findByIdIn(any())).willReturn(List.of());
        given(requestOptionRepository.findByIdIn(any())).willReturn(List.of());
        given(optionQueryService.resolveNames(anyString())).willReturn(Map.<Long, RequestOptionTranslation>of());
        givenOffices((RentalRequestResponse.Office) null);

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.regions()).isNotNull().isEmpty();
        assertThat(response.options()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("빈 목록은 조회 없이 빈 결과다")
    void empty_input_short_circuits() {
        assertThat(assembler().toResponses(List.of())).isEmpty();
    }

    // 대기 복귀 시각은 페이지째 한 번 읽어 요청 id 로 붙인다 — 순서가 아니라 id 로 맞춰야 목록에서 다른 요청에 붙지 않는다.
    @Test
    @DisplayName("대기 복귀 시각은 요청 id 로 붙고, 놓인 적 없는 요청은 null — 놓기 · 대기 복귀 사건만 묻는다")
    void last_released_at_is_matched_by_request_id() {
        RentalRequest first = withId(RentalRequest.builder().uuid("rq-1").requestNumber("RQ-261013-AAAAA1")
                .userId(10L).verificationId(99L).status(RentalRequestStatus.SUBMITTED).build(), 1L);
        RentalRequest second = withId(RentalRequest.builder().uuid("rq-2").requestNumber("RQ-261013-AAAAA2")
                .userId(10L).verificationId(99L).status(RentalRequestStatus.SUBMITTED).build(), 2L);
        given(claimedOfficeResolver.officesOf(any())).willReturn(Arrays.asList(null, null));
        given(claimEventRepository.findLastReleases(List.of(1L, 2L),
                List.of(RentalRequestClaimEventType.RELEASED, RentalRequestClaimEventType.RETURNED)))
                .willReturn(List.of(new RentalRequestLastRelease(2L, CLAIMED_AT)));

        List<RentalRequestResponse> responses = assembler().toResponses(List.of(first, second));

        assertThat(responses).extracting(RentalRequestResponse::requestUuid).containsExactly("rq-1", "rq-2");
        assertThat(responses).extracting(RentalRequestResponse::lastReleasedAt).containsExactly(null, CLAIMED_AT);
    }
}
