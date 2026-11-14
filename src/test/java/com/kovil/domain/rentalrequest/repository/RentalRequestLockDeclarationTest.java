package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.user.repository.UserRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 선착순 · 대기 복귀가 기대는 잠금 읽기를 **선언으로** 박는다. H2 · 목 리포지토리는 행 잠금도 스냅숏도 흉내 내지 못해,
 * @Lock 을 지워도 게이트의 다른 테스트는 전부 통과한다(실 MySQL 테스트만 빨개지고, 그 테스트는 게이트에서 건너뛴다).
 *   · 요청 행 잠금 읽기 — 동시에 누른 두 사무소가 여기서 줄을 서고 늦은 쪽이 정확한 코드로 진다.
 *   · 사무소의 진행 중 요청 잠금 읽기 — 정지 · 탈퇴의 스냅숏이 그사이 커밋된 잡기를 놓치면 정지된 사무소가 요청을 쥔 채 남는다.
 *   · 신청자의 열린 요청 잠금 읽기 — 탈퇴의 스냅숏이 그사이의 잡기를 놓치면 @Version 에 걸려 탈퇴가 409 로 실패한다.
 *   · 신청자 상태 공유 잠금 — 잡기가 요청 행보다 먼저 쥐어 신청자 탈퇴와 교착하지 않는다(배타로 바꾸면 알림 FK 검사와 맞물린다).
 */
class RentalRequestLockDeclarationTest {

    private static Lock lockOf(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        return method.getAnnotation(Lock.class);
    }

    @Test
    @DisplayName("잡기 · 놓기 · 종료의 요청 행, 대기 복귀의 진행 중 요청, 탈퇴의 열린 요청은 배타 잠금 읽기다")
    void request_rows_are_read_for_update() throws Exception {
        assertThat(lockOf(RentalRequestRepository.class, "findByUuidForUpdate", String.class))
                .isNotNull().extracting(Lock::value).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(lockOf(RentalRequestRepository.class, "findByOfficeIdAndStatus", Long.class, RentalRequestStatus.class))
                .isNotNull().extracting(Lock::value).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(lockOf(RentalRequestRepository.class, "findByUserIdAndStatusIn", Long.class, Collection.class))
                .isNotNull().extracting(Lock::value).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    // 상한 판정의 개수는 잠그지 않는다 — 진행 중 0건인 두 사무소가 같은 갭을 잠그고 교착한다(리포지토리 주석).
    @Test
    @DisplayName("사무소의 진행 중 개수는 잠그지 않는 일반 count 다")
    void office_count_is_a_plain_read() throws Exception {
        assertThat(lockOf(RentalRequestRepository.class, "countByOfficeIdAndStatus", Long.class, RentalRequestStatus.class))
                .isNull();
    }

    @Test
    @DisplayName("잡기가 먼저 쥐는 신청자 상태는 공유 잠금이다")
    void applicant_status_is_read_for_share() throws Exception {
        assertThat(lockOf(UserRepository.class, "findStatusByIdForShare", Long.class))
                .isNotNull().extracting(Lock::value).isEqualTo(LockModeType.PESSIMISTIC_READ);
    }
}
