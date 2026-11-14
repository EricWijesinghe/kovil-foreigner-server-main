package com.kovil.domain.partner.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * 부문 쿼리. 서비스 테스트는 이 저장소를 목으로 바꾸므로 **쿼리 자체는 여기서만 실행된다** —
 * 별칭 · 컬럼이 틀리면 로그인 전체가 런타임에 깨지는데 다른 테스트는 전부 초록이다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class PartnerDivisionRepositoryTest {

    @Autowired PartnerDivisionRepository repository;
    @Autowired EntityManager entityManager;

    private PartnerDivision save(String uuid, long userId, PartnerDivisionStatus status, LocalDateTime submittedAt) {
        return repository.saveAndFlush(PartnerDivision.builder().uuid(uuid).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(status)
                .submittedAt(submittedAt).build());
    }

    // 가입만 하고 서류를 내지 않은 건은 심사할 것이 없다 — 대기열을 어지럽히지 않는다.
    @Test
    @DisplayName("심사 대기열은 제출된 것만, 제출이 오래된 순으로 준다")
    void queue_shows_submitted_only_oldest_first() {
        save("pd-late", 1L, PartnerDivisionStatus.PENDING, LocalDateTime.of(2026, 10, 6, 12, 0));
        save("pd-early", 2L, PartnerDivisionStatus.PENDING, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-draft", 3L, PartnerDivisionStatus.PENDING, null);

        var page = repository.findSubmitted(null, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(PartnerDivision::getUuid)
                .containsExactly("pd-early", "pd-late");
    }

    @Test
    @DisplayName("상태 · 부문 필터가 동작한다")
    void queue_filters_by_status_and_division() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 5, 9, 0);
        save("pd-pending", 1L, PartnerDivisionStatus.PENDING, at);
        save("pd-approved", 2L, PartnerDivisionStatus.APPROVED, at);
        save("pd-suspended", 3L, PartnerDivisionStatus.SUSPENDED, at);

        assertThat(repository.findSubmitted(PartnerDivisionStatus.PENDING, null, PageRequest.of(0, 20))
                .getContent()).extracting(PartnerDivision::getUuid).containsExactly("pd-pending");
        assertThat(repository.findSubmitted(null, PartnerDivisionType.REAL_ESTATE_BROKER, PageRequest.of(0, 20))
                .getContent()).hasSize(3);
    }

    /*
     * ★ 토큰에 실릴 부문이 여기서 나온다. 승인된 것만 — 심사 중 · 반려 · 정지가 섞이면 그 순간
     *   심사를 거치지 않은 사람에게 매물 관리가 열린다.
     */
    @Test
    @DisplayName("토큰용 잠금 읽기는 승인된 부문의 이름만 돌려준다")
    void approved_names_for_token() {
        save("pd-1", 7L, PartnerDivisionStatus.APPROVED, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-other", 8L, PartnerDivisionStatus.APPROVED, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-2", 9L, PartnerDivisionStatus.SUSPENDED, LocalDateTime.of(2026, 10, 5, 9, 0));
        entityManager.clear();

        assertThat(repository.findApprovedDivisionNamesForUpdate(7L)).containsExactly("REAL_ESTATE_BROKER");
        assertThat(repository.findApprovedDivisionNamesForUpdate(9L)).isEmpty();
        assertThat(repository.findApprovedTypes(7L)).containsExactly(PartnerDivisionType.REAL_ESTATE_BROKER);
        assertThat(repository.findApprovedTypes(9L)).isEmpty();
    }

    // 심사 화면의 "같은 등록번호의 다른 신청"이 대표자들의 부문을 한 번에 찾는다.
    @Test
    @DisplayName("대표자 여럿의 공인중개사 부문을 한 번에 찾는다")
    void finds_divisions_of_many_users() {
        save("pd-7", 7L, PartnerDivisionStatus.PENDING, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-8", 8L, PartnerDivisionStatus.APPROVED, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-9", 9L, PartnerDivisionStatus.PENDING, null);

        assertThat(repository.findByUserIdInAndDivision(List.of(7L, 8L),
                PartnerDivisionType.REAL_ESTATE_BROKER))
                .extracting(PartnerDivision::getUuid).containsExactlyInAnyOrder("pd-7", "pd-8");
    }

    /*
     * 변경 승인이 사무소를 잠근 뒤 부문의 **지금** 상태를 묻는다(잠금 읽기). 다른 사람 · 다른 부문은 섞이지 않고,
     * 부문이 없으면 비어 있다(승인할 수 없다).
     */
    @Test
    @DisplayName("부문 상태 잠금 읽기는 그 사람 · 그 부문의 상태 이름을 준다")
    void status_for_update() {
        save("pd-7", 7L, PartnerDivisionStatus.SUSPENDED, LocalDateTime.of(2026, 10, 5, 9, 0));
        save("pd-8", 8L, PartnerDivisionStatus.APPROVED, LocalDateTime.of(2026, 10, 5, 9, 0));
        entityManager.clear();

        assertThat(repository.findStatusForUpdate(7L, "REAL_ESTATE_BROKER")).contains("SUSPENDED");
        assertThat(repository.findStatusForUpdate(8L, "REAL_ESTATE_BROKER")).contains("APPROVED");
        assertThat(repository.findStatusForUpdate(9L, "REAL_ESTATE_BROKER")).isEmpty();
    }

    // 마지막 승인 시각 — 승인 · 복구가 칼럼에 남긴다(심사 화면이 보여 준다).
    @Test
    @DisplayName("승인하면 마지막 승인 시각이 저장된다")
    void last_approved_at_is_persisted() {
        PartnerDivision saved = save("pd-a", 7L, PartnerDivisionStatus.PENDING, LocalDateTime.of(2026, 10, 5, 9, 0));
        saved.approve(1L, LocalDateTime.of(2026, 10, 6, 9, 0));
        repository.saveAndFlush(saved);
        entityManager.clear();

        assertThat(repository.findByUuid("pd-a").orElseThrow().getLastApprovedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 0));
    }

    /*
     * ★ 부문 행의 사무소 연결(office_id) — 가입 때 채우고, 처분(승인 · 정지 · 탈퇴 반려)을 거쳐도 남는다. 탈퇴가 대표자 연결을
     *   지워도 정지 이력이 이 연결로 번호를 찾는다. 매핑이 updatable = false 라 엔티티 쪽 값이 바뀌어도 UPDATE 에 실리지 않는다
     *   (낡은 엔티티의 갱신이 마이그레이션이 채운 값을 덮지 않는다). 사무소가 없는 부문은 비어 있다.
     */
    @Test
    @DisplayName("부문의 사무소 연결은 저장되고, 처분을 거쳐도 남고, 엔티티 갱신으로 바뀌지 않는다")
    void office_link_is_kept_through_dispositions() {
        PartnerDivision saved = repository.saveAndFlush(PartnerDivision.builder().uuid("pd-o").userId(7L)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).officeId(70L)
                .status(PartnerDivisionStatus.PENDING).submittedAt(LocalDateTime.of(2026, 10, 5, 9, 0)).build());
        save("pd-none", 8L, PartnerDivisionStatus.PENDING, null);
        saved.approve(1L, LocalDateTime.of(2026, 10, 6, 9, 0));
        saved.suspend(1L, "허위 매물", LocalDateTime.of(2026, 10, 7, 9, 0));
        ReflectionTestUtils.setField(saved, "officeId", 999L);
        repository.saveAndFlush(saved);
        entityManager.clear();

        PartnerDivision reloaded = repository.findByUuid("pd-o").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PartnerDivisionStatus.SUSPENDED);
        assertThat(reloaded.getOfficeId()).isEqualTo(70L);
        assertThat(repository.findByUuid("pd-none").orElseThrow().getOfficeId()).isNull();
    }

    // @Version — 두 관리자가 같은 건을 동시에 처리하면 늦은 쪽을 409 로 돌려보내는 축이다.
    @Test
    @DisplayName("버전 칼럼이 저장 때 채워지고 갱신마다 오른다")
    void version_is_maintained() {
        PartnerDivision saved = save("pd-v", 7L, PartnerDivisionStatus.PENDING, LocalDateTime.of(2026, 10, 5, 9, 0));
        Long first = saved.getVersion();

        saved.approve(1L, LocalDateTime.of(2026, 10, 6, 9, 0));
        repository.saveAndFlush(saved);

        assertThat(first).isNotNull();
        assertThat(saved.getVersion()).isGreaterThan(first);
    }
}
