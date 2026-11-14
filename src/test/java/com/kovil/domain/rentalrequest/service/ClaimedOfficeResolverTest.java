package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.dto.response.RentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.crypto.Encryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/*
 * 유학생 화면의 "맡은 사무소". 상호 · 대표자명 · 소재지 · 대표번호는 맡은 뒤 계속 보이고,
 * **대표자 휴대폰은 진행 중일 때만** 실린다(종료 · 취소 · 대기 복귀면 가린다 — PO 확정 2026-10-07).
 */
@ExtendWith(MockitoExtension.class)
class ClaimedOfficeResolverTest {

    private static final Long OFFICE_ID = 7L;
    private static final Long CEO_ID = 70L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 10, 0);

    @Mock BrokerageOfficeRepository officeRepository;
    @Mock BrokerProfileRepository brokerProfileRepository;
    @Mock UserRepository userRepository;
    @Mock Encryptor encryptor;

    private ClaimedOfficeResolver resolver() {
        return new ClaimedOfficeResolver(officeRepository, brokerProfileRepository, userRepository, encryptor);
    }

    private static <T> T withId(T entity, Long id) {
        try {
            Class<?> type = entity.getClass();
            Field field = null;
            while (field == null && type != null) {
                try {
                    field = type.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    private static RentalRequest request(Long id) {
        return withId(RentalRequest.builder()
                .uuid("r-" + id).requestNumber("RQ-261007-" + id).userId(10L).verificationId(99L)
                .status(RentalRequestStatus.SUBMITTED).build(), id);
    }

    private static RentalRequest claimed(Long id) {
        RentalRequest request = request(id);
        request.claim(OFFICE_ID, AT);
        return request;
    }

    private static BrokerageOffice office() {
        return withId(BrokerageOffice.builder()
                .uuid("o-1").regNo("99999-2026-00001").name("행복공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 광진구 능동로 000").tel("02-000-0000").build(), OFFICE_ID);
    }

    private static User ceo(UserStatus status, LocalDateTime deletedAt) {
        return withId(User.builder()
                .uuid("u-ceo").role(UserRole.PARTNER).status(status).preferredLanguage("ko")
                .phone("cipher-phone").deletedAt(deletedAt).build(), CEO_ID);
    }

    private void givenOffice() {
        given(officeRepository.findAllById(any())).willReturn(List.of(office()));
    }

    private void givenCeo(User ceo) {
        given(brokerProfileRepository.findByOfficeIdIn(any())).willReturn(List.of(
                BrokerProfile.builder().userId(CEO_ID).officeId(OFFICE_ID).build()));
        given(userRepository.findAllById(any())).willReturn(List.of(ceo));
    }

    @Nested
    @DisplayName("진행 중")
    class InProgress {

        @Test
        @DisplayName("상호 · 대표자명 · 소재지 · 대표번호와 복호화한 대표자 휴대폰이 실린다")
        void carries_public_info_and_ceo_phone() {
            givenOffice();
            givenCeo(ceo(UserStatus.ACTIVE, null));
            given(encryptor.decrypt("cipher-phone")).willReturn("010-0000-1234");

            List<RentalRequestResponse.Office> offices = resolver().officesOf(List.of(claimed(1L)));

            assertThat(offices).containsExactly(new RentalRequestResponse.Office(
                    "행복공인중개사사무소", "홍길동", "서울특별시 광진구 능동로 000", "02-000-0000", "010-0000-1234"));
        }

        // 정지 · 탈퇴는 그 사무소의 진행 중 요청을 대기로 돌린다. 그 사이의 순간에도 정지된 사람의 번호가 나가면 안 된다.
        @Test
        @DisplayName("대표자 계정이 정지됐으면 휴대폰은 비우고 대표번호는 남긴다")
        void suspended_ceo_phone_is_hidden() {
            givenOffice();
            givenCeo(ceo(UserStatus.SUSPENDED, null));

            RentalRequestResponse.Office office = resolver().officesOf(List.of(claimed(1L))).get(0);

            assertThat(office.ceoPhone()).isNull();
            assertThat(office.tel()).isEqualTo("02-000-0000");
            then(encryptor).should(never()).decrypt(anyString());
        }

        @Test
        @DisplayName("대표자가 탈퇴했으면 휴대폰은 비운다")
        void withdrawn_ceo_phone_is_hidden() {
            givenOffice();
            givenCeo(ceo(UserStatus.WITHDRAWN, AT));

            assertThat(resolver().officesOf(List.of(claimed(1L))).get(0).ceoPhone()).isNull();
            then(encryptor).should(never()).decrypt(anyString());
        }

        @Test
        @DisplayName("사무소에 대표자 연결이 없으면 휴대폰은 비운다")
        void missing_ceo_link_hides_phone() {
            givenOffice();
            given(brokerProfileRepository.findByOfficeIdIn(any())).willReturn(List.of());

            RentalRequestResponse.Office office = resolver().officesOf(List.of(claimed(1L))).get(0);

            assertThat(office.ceoPhone()).isNull();
            assertThat(office.name()).isEqualTo("행복공인중개사사무소");
        }

        // 복호화가 실패해도 요청 화면 전체가 죽으면 안 된다 — 그 번호만 비운다.
        @Test
        @DisplayName("복호화가 실패하면 휴대폰만 비우고 나머지는 그대로 낸다")
        void decrypt_failure_hides_phone_only() {
            givenOffice();
            givenCeo(ceo(UserStatus.ACTIVE, null));
            given(encryptor.decrypt("cipher-phone")).willThrow(new IllegalStateException("bad key"));

            RentalRequestResponse.Office office = resolver().officesOf(List.of(claimed(1L))).get(0);

            assertThat(office.ceoPhone()).isNull();
            assertThat(office.name()).isEqualTo("행복공인중개사사무소");
            assertThat(office.tel()).isEqualTo("02-000-0000");
        }
    }

    @Nested
    @DisplayName("진행 중이 아님")
    class NotInProgress {

        // 종료 · 취소된 요청은 맡았던 사무소를 그대로 보여 주지만 서로의 번호는 더 오가지 않는다.
        @Test
        @DisplayName("종료 · 취소된 요청은 사무소 공개 정보만 싣고 휴대폰은 읽지도 않는다")
        void closed_and_canceled_hide_ceo_phone() {
            givenOffice();
            RentalRequest closed = claimed(1L);
            closed.close(OFFICE_ID, RentalRequestOutcome.FOUND, AT.plusDays(1));
            RentalRequest canceled = claimed(2L);
            canceled.cancel("직접 구했습니다", AT.plusDays(1));

            List<RentalRequestResponse.Office> offices = resolver().officesOf(List.of(closed, canceled));

            assertThat(offices).allSatisfy(office -> {
                assertThat(office.name()).isEqualTo("행복공인중개사사무소");
                assertThat(office.ceoName()).isEqualTo("홍길동");
                assertThat(office.tel()).isEqualTo("02-000-0000");
                assertThat(office.ceoPhone()).isNull();
            });
            then(brokerProfileRepository).should(never()).findByOfficeIdIn(any());
            then(userRepository).should(never()).findAllById(any());
            then(encryptor).should(never()).decrypt(anyString());
        }

        @Test
        @DisplayName("대기 요청(아무도 안 잡음 · 놓아서 돌아옴)은 사무소 자리가 null 이고 아무것도 읽지 않는다")
        void waiting_requests_have_no_office() {
            RentalRequest released = claimed(2L);
            released.release(OFFICE_ID);

            List<RentalRequestResponse.Office> offices = resolver().officesOf(List.of(request(1L), released));

            assertThat(offices).hasSize(2).containsOnlyNulls();
            then(officeRepository).should(never()).findAllById(any());
        }
    }

    @Test
    @DisplayName("목록은 요청 순서대로 자리를 채우고, 같은 사무소는 한 번만 읽는다")
    void batch_keeps_order_and_reads_once() {
        given(officeRepository.findAllById(any())).willReturn(List.of(office()));
        givenCeo(ceo(UserStatus.ACTIVE, null));
        given(encryptor.decrypt("cipher-phone")).willReturn("010-0000-1234");
        RentalRequest closed = claimed(3L);
        closed.close(OFFICE_ID, RentalRequestOutcome.NOT_PROCEEDED, AT.plusDays(1));

        List<RentalRequestResponse.Office> offices =
                resolver().officesOf(List.of(request(1L), claimed(2L), closed));

        assertThat(offices.get(0)).isNull();
        assertThat(offices.get(1).ceoPhone()).isEqualTo("010-0000-1234");
        assertThat(offices.get(2).ceoPhone()).isNull();
        assertThat(offices.get(2).name()).isEqualTo("행복공인중개사사무소");
        then(officeRepository).should().findAllById(List.of(OFFICE_ID));
    }
}
