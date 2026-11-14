package com.kovil.domain.partner.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.partner.entity.BrokerageOfficeChangeRequest;
import com.kovil.domain.partner.enums.OfficeChangeRequestStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 변경 신청 쿼리. 서비스 테스트는 이 저장소를 목으로 바꾸므로 쿼리 자체는 여기서만 실행된다 — 대기 건 조회가 틀리면
 * "사무소당 대기 건 하나"가 깨지거나(남의 사무소 건을 덮는다) 판정이 난 건을 덮는다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class BrokerageOfficeChangeRequestRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 7, 9, 0);

    @Autowired BrokerageOfficeChangeRequestRepository repository;
    @Autowired EntityManager entityManager;

    private BrokerageOfficeChangeRequest save(String uuid, long officeId, OfficeChangeRequestStatus status,
                                              LocalDateTime submittedAt) {
        return repository.saveAndFlush(BrokerageOfficeChangeRequest.builder().uuid(uuid).officeId(officeId)
                .requestedBy(7L).regNo("99999-2026-00009").name("새상호공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").licenseFileId(66L).status(status).submittedAt(submittedAt).build());
    }

    // 대기 건은 그 사무소의 PENDING 하나다 — 판정이 난 건 · 다른 사무소의 대기 건을 집으면 남의 것을 덮는다.
    @Test
    @DisplayName("대기 건 조회는 그 사무소의 PENDING 만 준다")
    void pending_lookup_is_scoped_to_office_and_status() {
        save("cr-approved", 70L, OfficeChangeRequestStatus.APPROVED, AT.minusDays(3));
        save("cr-rejected", 70L, OfficeChangeRequestStatus.REJECTED, AT.minusDays(2));
        save("cr-other", 80L, OfficeChangeRequestStatus.PENDING, AT.minusDays(1));
        save("cr-pending", 70L, OfficeChangeRequestStatus.PENDING, AT);
        entityManager.clear();

        assertThat(repository.findPendingForUpdate(70L)).get()
                .extracting(BrokerageOfficeChangeRequest::getUuid).isEqualTo("cr-pending");
        assertThat(repository.findPendingForUpdate(90L)).isEmpty();
    }

    @Test
    @DisplayName("판정이 난 건만 있으면 대기 건이 없다")
    void no_pending_when_all_decided() {
        save("cr-approved", 70L, OfficeChangeRequestStatus.APPROVED, AT.minusDays(3));
        save("cr-rejected", 70L, OfficeChangeRequestStatus.REJECTED, AT.minusDays(2));
        entityManager.clear();

        assertThat(repository.findPendingForUpdate(70L)).isEmpty();
    }

    // 파트너 화면의 "최근 변경 신청" — 상태와 관계없이 가장 나중에 만든 한 건.
    @Test
    @DisplayName("최근 변경 신청은 그 사무소에서 가장 나중에 만든 건이다")
    void latest_is_the_last_created() {
        save("cr-1", 70L, OfficeChangeRequestStatus.APPROVED, AT.minusDays(3));
        save("cr-2", 70L, OfficeChangeRequestStatus.REJECTED, AT.minusDays(1));
        save("cr-other", 80L, OfficeChangeRequestStatus.PENDING, AT);

        assertThat(repository.findFirstByOfficeIdOrderByIdDesc(70L)).get()
                .extracting(BrokerageOfficeChangeRequest::getUuid).isEqualTo("cr-2");
        assertThat(repository.findFirstByOfficeIdOrderByIdDesc(90L)).isEmpty();
    }

    // 오래 기다린 제출이 먼저 — 대기 중에 다시 낸 건은 제출 시각이 새로 찍혀 뒤로 간다.
    @Test
    @DisplayName("대기열은 상태로 거르고 제출이 오래된 순으로 준다")
    void queue_orders_by_submission() {
        save("cr-late", 70L, OfficeChangeRequestStatus.PENDING, AT);
        save("cr-early", 80L, OfficeChangeRequestStatus.PENDING, AT.minusHours(5));
        save("cr-done", 90L, OfficeChangeRequestStatus.APPROVED, AT.minusDays(1));

        assertThat(repository.findQueue(OfficeChangeRequestStatus.PENDING, PageRequest.of(0, 20)).getContent())
                .extracting(BrokerageOfficeChangeRequest::getUuid).containsExactly("cr-early", "cr-late");
        assertThat(repository.findQueue(OfficeChangeRequestStatus.APPROVED, PageRequest.of(0, 20)).getContent())
                .extracting(BrokerageOfficeChangeRequest::getUuid).containsExactly("cr-done");
    }

    /*
     * 심사 화면의 "같은 번호의 다른 신청" — 그 번호를 바꿀 번호로 둔 대기 건만, 먼저 낸 것부터. 판정이 난 건이나 다른 번호의 건이
     * 섞이면 이미 끝난 신청이 경쟁자로 보인다.
     */
    @Test
    @DisplayName("같은 번호의 변경 신청은 그 번호의 대기 건만 제출이 오래된 순으로 준다")
    void same_number_lookup_is_pending_only_in_submission_order() {
        save("cr-late", 70L, OfficeChangeRequestStatus.PENDING, AT);
        save("cr-early", 80L, OfficeChangeRequestStatus.PENDING, AT.minusHours(3));
        save("cr-rejected", 90L, OfficeChangeRequestStatus.REJECTED, AT.minusDays(1));
        repository.saveAndFlush(BrokerageOfficeChangeRequest.builder().uuid("cr-other-number").officeId(95L)
                .requestedBy(8L).regNo("99999-2026-00010").name("다른번호공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").licenseFileId(67L).status(OfficeChangeRequestStatus.PENDING)
                .submittedAt(AT.minusDays(2)).build());
        entityManager.clear();

        assertThat(repository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc("99999-2026-00009",
                OfficeChangeRequestStatus.PENDING))
                .extracting(BrokerageOfficeChangeRequest::getUuid).containsExactly("cr-early", "cr-late");
        assertThat(repository.findByRegNoAndStatusOrderBySubmittedAtAscIdAsc("99999-2026-00011",
                OfficeChangeRequestStatus.PENDING)).isEmpty();
    }

    // 덮어쓰면 version 이 오른다 — 그 전에 상세를 열어 둔 관리자의 승인이 409 로 돌아가는 축이다.
    @Test
    @DisplayName("덮어쓰면 version 이 오른다")
    void overwrite_bumps_version() {
        BrokerageOfficeChangeRequest saved = save("cr-v", 70L, OfficeChangeRequestStatus.PENDING, AT.minusDays(1));
        Long first = saved.getVersion();

        saved.overwrite("99999-2026-00010", "다른상호공인중개사사무소", "홍길동", "서울특별시 종로구", null, 77L, AT);
        repository.saveAndFlush(saved);

        assertThat(first).isNotNull();
        assertThat(saved.getVersion()).isGreaterThan(first);
        entityManager.clear();
        BrokerageOfficeChangeRequest reloaded = repository.findByUuid("cr-v").orElseThrow();
        assertThat(reloaded.getRegNo()).isEqualTo("99999-2026-00010");
        assertThat(reloaded.getSubmittedAt()).isEqualTo(AT);
    }
}
