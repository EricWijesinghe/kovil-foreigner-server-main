package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.region.repository.RegionRepository;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestOption;
import com.kovil.domain.rentalrequest.entity.RentalRequestRegion;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.entity.RequestOptionTranslation;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestOptionRepository;
import com.kovil.domain.rentalrequest.repository.RentalRequestRegionRepository;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.region.entity.Region;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.crypto.Encryptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 표시의 스냅샷 완결(V13) — **마스터가 어떻게 바뀌어도 제출된 요청의 표시는 변하지 않는다.**
 *
 * <p>ko 화면은 ko 박제, 그 외 화면은 제출자가 본 언어의 박제를 그린다. live 마스터는
 * code 축과 백필 전 저장분(스냅샷 NULL)의 폴백일 뿐이다. 마스터 행이 아예 없어도
 * (삭제 가드·FK가 막지만, 마지막 방어선으로) 답은 스냅샷으로 그려진다.
 */
@ExtendWith(MockitoExtension.class)
class SnapshotDisplayTest {

    @Mock RegionRepository regionRepository;
    @Mock RequestOptionRepository requestOptionRepository;
    @Mock RentalRequestRegionRepository requestRegionRepository;
    @Mock RentalRequestOptionRepository requestOptionLinkRepository;
    @Mock RequestOptionQueryService optionQueryService;
    @Mock BrokerageOfficeRepository officeRepository;
    @Mock BrokerProfileRepository brokerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock Encryptor encryptor;
    @Mock RentalRequestClaimEventRepository claimEventRepository;

    // 맡은 사무소가 없는 요청이라 사무소 조회는 일어나지 않는다 — 표시 스냅샷만 본다.
    private RentalRequestAssembler assembler() {
        return new RentalRequestAssembler(regionRepository, requestOptionRepository,
                requestRegionRepository, requestOptionLinkRepository, optionQueryService,
                new ClaimedOfficeResolver(officeRepository, brokerProfileRepository, userRepository, encryptor),
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
                .status(RentalRequestStatus.CLOSED).moveInDate(LocalDate.of(2026, 10, 1))
                .build(), 500L);
    }

    private static RentalRequestOption answerLink() {
        return RentalRequestOption.builder()
                .rentalRequestId(500L).requestOptionId(11L)
                .questionTextSnapshot("어떤 집을 찾으세요?").answerTextSnapshot("원룸")
                .questionTextSnapshotUser("What type of place?").answerTextSnapshotUser("Studio")
                .build();
    }

    private void givenLinks(RentalRequestOption link, List<RequestOption> masters) {
        given(requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of(
                RentalRequestRegion.builder().rentalRequestId(500L).regionId(20L)
                        .regionNameSnapshot("광진구").regionNameSnapshotUser("Gwangjin-gu").build()));
        given(requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(any()))
                .willReturn(List.of(link));
        given(regionRepository.findByIdIn(any())).willReturn(List.of());
        given(requestOptionRepository.findByIdIn(any())).willReturn(masters);
        given(optionQueryService.resolveNames(anyString())).willReturn(Map.<Long, RequestOptionTranslation>of());
    }

    @Test
    @DisplayName("마스터 행이 없어도 답은 스냅샷으로 그려진다 — 탈락하지 않는다")
    void answer_survives_missing_master_row() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        givenLinks(answerLink(), List.of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.name()).isEqualTo("원룸");
            assertThat(option.code()).isNull();
        });
        assertThat(response.regions()).singleElement()
                .satisfies(region -> assertThat(region.name()).isEqualTo("광진구"));
    }

    @Test
    @DisplayName("ko 외 화면은 제출자가 본 언어의 박제를 그린다")
    void non_korean_screen_uses_user_snapshot() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        givenLinks(answerLink(), List.of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement()
                .satisfies(option -> assertThat(option.name()).isEqualTo("Studio"));
        assertThat(response.regions()).singleElement()
                .satisfies(region -> assertThat(region.name()).isEqualTo("Gwangjin-gu"));
    }

    @Test
    @DisplayName("마스터 문구를 고쳐도 표시는 박제 그대로다 — live 이름은 아예 묻지 않는다")
    void master_edits_do_not_change_display() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        // 관리자가 "원룸"을 "오픈형 원룸"으로 고친 상태 — 그래도 박제("원룸")가 나가야 한다.
        givenLinks(answerLink(),
                List.of(withId(RequestOption.builder().parentId(1L).code("ONE_ROOM").build(), 11L)));

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.name()).isEqualTo("원룸");
            assertThat(option.code()).isEqualTo("ONE_ROOM");
        });
        then(optionQueryService).should(never()).nameOf(any(), any());
    }

    @Test
    @DisplayName("직접 입력 답은 질문 문구가 표시명이다 — 값은 value 필드로 나간다")
    void direct_answer_display_name_is_question_text() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        RentalRequestOption link = RentalRequestOption.builder()
                .rentalRequestId(500L).requestOptionId(40L).textValue("직장 이직")
                .questionTextSnapshot("이사 이유가 무엇인가요?")
                .questionTextSnapshotUser("Why are you moving?")
                .build();
        givenLinks(link, List.of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.name()).isEqualTo("이사 이유가 무엇인가요?");
            assertThat(option.textValue()).isEqualTo("직장 이직");
        });
    }

    @Test
    @DisplayName("행 라벨과 답 문구가 분리돼 내려간다 — 상세 화면이 '질문: 답'을 조립하지 않게")
    void question_and_answer_names_are_separate() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        givenLinks(answerLink(), List.of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.questionName()).isEqualTo("어떤 집을 찾으세요?");
            assertThat(option.answerName()).isEqualTo("원룸");
        });
    }

    @Test
    @DisplayName("직접 입력 답은 answerName이 null이다 — 값 컬럼이 정본이라 문구를 지어내지 않는다")
    void direct_answer_has_no_answer_name() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        RentalRequestOption link = RentalRequestOption.builder()
                .rentalRequestId(500L).requestOptionId(40L).numberValue(750000L)
                .questionTextSnapshot("월세 예산은 얼마인가요?")
                .build();
        givenLinks(link, List.of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.questionName()).isEqualTo("월세 예산은 얼마인가요?");
            assertThat(option.answerName()).isNull();
            assertThat(option.numberValue()).isEqualTo(750000L);
        });
    }

    @Test
    @DisplayName("ADDRESS 답은 지역 code를 함께 내려준다 — 수정 화면의 기계 축")
    void address_answer_carries_region_code() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        RentalRequestOption link = RentalRequestOption.builder()
                .rentalRequestId(500L).requestOptionId(40L).regionId(30L)
                .questionTextSnapshot("직장은 어디인가요?").answerTextSnapshot("마포구")
                .build();
        given(requestRegionRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of());
        given(requestOptionLinkRepository.findByRentalRequestIdInOrderByIdAsc(any())).willReturn(List.of(link));
        given(regionRepository.findByIdIn(any())).willReturn(List.of(withId(
                Region.builder()
                        .parentId(1L).code("11440").nameKo("마포구").nameEn("Mapo-gu")
                        .sortOrder(1).isActive(true).build(), 30L)));
        given(requestOptionRepository.findByIdIn(any())).willReturn(List.of());
        given(optionQueryService.resolveNames(anyString())).willReturn(Map.<Long, RequestOptionTranslation>of());

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement().satisfies(option -> {
            assertThat(option.regionCode()).isEqualTo("11440");
            assertThat(option.name()).isEqualTo("마포구");
        });
    }

    @Test
    @DisplayName("스냅샷이 없는 과거 행(백필 전)은 live 마스터로 폴백한다")
    void legacy_rows_fall_back_to_live_master() {
        LocaleContextHolder.setLocale(Locale.KOREAN);
        RentalRequestOption bare = RentalRequestOption.builder()
                .rentalRequestId(500L).requestOptionId(11L).build();
        RequestOption master = withId(RequestOption.builder().parentId(1L).code("ONE_ROOM").build(), 11L);
        givenLinks(bare, List.of(master));
        given(optionQueryService.nameOf(any(), any())).willReturn("원룸");

        RentalRequestResponse response = assembler().toResponse(request());

        assertThat(response.options()).singleElement()
                .satisfies(option -> assertThat(option.name()).isEqualTo("원룸"));
    }
}
