package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.rentalrequest.dto.request.RequestAnswerEntry;
import com.kovil.domain.rentalrequest.entity.RequestOption;
import com.kovil.domain.rentalrequest.enums.ExtraInputType;
import com.kovil.domain.rentalrequest.exception.RentalRequestException;
import com.kovil.domain.rentalrequest.repository.RequestOptionRepository;
import com.kovil.domain.region.repository.RegionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * DATE 질문의 "지난 날짜 허용" 축(V16) 결과 격자.
 *
 * <p>★ 과거 거부는 코드가 아니라 관리자가 질문마다 정한다 — "입주 희망일"은 미래,
 * "입국일이 언제였나요?"는 과거가 답이다. 그래서 기본은 허용이고, 거부로 정한
 * 질문만 오늘 이전을 {@code OPTION_DATE_IN_PAST}로 막는다. **오늘은 항상 허용**이다
 * (자정 직후 제출이 시간대 차이로 튕기면 유저는 원인을 알 수 없다 — 경계를 여기 고정).
 */
@ExtendWith(MockitoExtension.class)
class DatePastToggleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    @Mock RequestOptionRepository requestOptionRepository;
    @Mock RegionRepository regionRepository;
    @InjectMocks RequestAnswerResolver resolver;

    private RequestOption dateQuestion(Boolean allowPast) {
        RequestOption question = RequestOption.builder()
                .code("MOVE_IN_HOPE").selectionType("DATE").sortOrder(1).active(true)
                .required(true).extraInputType(ExtraInputType.NONE.name()).extraRequired(false)
                .inputAllowPast(allowPast)
                .build();
        ReflectionTestUtils.setField(question, "id", 60L);
        // 서비스 시간대의 "오늘"을 고정한다 — 실제 시계로 경계 테스트를 하면 날짜가 바뀔 때 거짓말한다.
        ReflectionTestUtils.setField(resolver, "clock",
                Clock.fixed(Instant.parse("2026-08-26T03:00:00Z"), ZoneId.of("Asia/Seoul")));
        given(requestOptionRepository.findByActiveTrueOrderBySortOrderAscIdAsc()).willReturn(List.of(question));
        return question;
    }

    private static RequestAnswerEntry on(LocalDate date) {
        return new RequestAnswerEntry("MOVE_IN_HOPE", null, null, date, null);
    }

    @Test
    @DisplayName("과거 거부 질문에 어제를 보내면 OPTION_DATE_IN_PAST다")
    void past_rejected_when_disallowed() {
        RequestOption question = dateQuestion(false);

        assertThatThrownBy(() -> resolver.resolve(List.of(question), List.of(on(TODAY.minusDays(1)))))
                .isInstanceOf(RentalRequestException.class)
                .satisfies(e -> assertThat(((RentalRequestException) e).getErrorCode().getCode())
                        .isEqualTo("OPTION_DATE_IN_PAST"));
    }

    @Test
    @DisplayName("과거 거부 질문이라도 오늘은 답이 된다 — 경계는 '이전'이지 '이하'가 아니다")
    void today_passes_even_when_disallowed() {
        RequestOption question = dateQuestion(false);

        List<RequestAnswerResolver.ResolvedAnswer> answers =
                resolver.resolve(List.of(question), List.of(on(TODAY)));

        assertThat(answers).singleElement()
                .satisfies(answer -> assertThat(answer.dateValue()).isEqualTo(TODAY));
    }

    @Test
    @DisplayName("과거 허용(기본) 질문은 어제도 답이 된다 — 입국일 같은 질문의 존재 이유")
    void past_passes_when_allowed() {
        RequestOption question = dateQuestion(true);

        List<RequestAnswerResolver.ResolvedAnswer> answers =
                resolver.resolve(List.of(question), List.of(on(TODAY.minusDays(30))));

        assertThat(answers).singleElement()
                .satisfies(answer -> assertThat(answer.dateValue()).isEqualTo(TODAY.minusDays(30)));
    }

    @Test
    @DisplayName("축이 정해지지 않은 구 데이터(null)는 허용으로 읽는다 — 기존 질문의 동작을 바꾸지 않는다")
    void null_axis_means_allowed() {
        RequestOption question = dateQuestion(null);

        assertThat(resolver.resolve(List.of(question), List.of(on(TODAY.minusDays(1))))).hasSize(1);
    }
}
