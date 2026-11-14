package com.kovil.domain.listing.repository;

import com.kovil.common.config.JpaAuditingConfig;
import com.kovil.domain.listing.entity.BrokerageOffice;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * 등록번호는 **승인에서** 막는다(PO 확정 2026-10-06) — 그 규칙을 실제 DB 로 고정한다.
 *   · 같은 번호로 여러 사무소가 신청할 수 있다(reg_no 는 유니크가 아니다).
 *   · 한 번호는 한 사무소만 잡는다(uq_bo_claimed_reg_no) — 동시 승인 둘 중 하나만 이긴다.
 *     서비스는 위반의 제약 이름으로 409 를 고르므로, 이름이 그대로 올라오는지까지 본다.
 *
 * ★ 이 테스트의 DB 는 H2 + 엔티티 매핑이다. 운영 스키마의 같은 제약은 V26 이 만든다 — 둘 다 같은 이름이어야
 *   번역이 운영에서도 맞는다(엔티티의 @UniqueConstraint 와 V26 의 UNIQUE KEY 이름).
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class BrokerageOfficeRegNoRepositoryTest {

    private static final String REG_NO = "99999-2026-00002";

    @Autowired BrokerageOfficeRepository officeRepository;

    private BrokerageOffice office(String uuid, String regNo) {
        return officeRepository.saveAndFlush(BrokerageOffice.builder().uuid(uuid).regNo(regNo)
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 관악구").tel("02-000-0000").build());
    }

    @Test
    @DisplayName("같은 등록번호로 여러 사무소가 신청할 수 있다 — 잡지 않은 번호(NULL)는 몇 개든 겹친다")
    void unclaimed_applications_may_share_reg_no() {
        office("o-1", REG_NO);
        office("o-2", REG_NO);
        office("o-3", REG_NO);

        assertThat(officeRepository.findAll()).hasSize(3);
        assertThat(officeRepository.existsByClaimedRegNo(REG_NO)).isFalse();
    }

    @Test
    @DisplayName("한 번호는 한 사무소만 잡는다 — 두 번째 잡기는 uq_bo_claimed_reg_no 위반으로 올라온다")
    void only_one_office_claims_a_number() {
        BrokerageOffice first = office("o-1", REG_NO);
        BrokerageOffice second = office("o-2", REG_NO);
        first.claimRegNo();
        officeRepository.saveAndFlush(first);

        second.claimRegNo();

        assertThatThrownBy(() -> officeRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(e.getCause()).isInstanceOfSatisfying(ConstraintViolationException.class,
                        violation -> assertThat(violation.getConstraintName()).isNotNull()
                                .satisfies(name -> assertThat(name.toLowerCase()).contains("uq_bo_claimed_reg_no"))));
    }

    @Test
    @DisplayName("잡은 번호만 중복으로 센다 — 자기 사무소는 빼고 센다")
    void exists_counts_claims_only() {
        BrokerageOffice holder = office("o-1", REG_NO);
        BrokerageOffice applicant = office("o-2", REG_NO);
        holder.claimRegNo();
        officeRepository.saveAndFlush(holder);

        assertThat(officeRepository.existsByClaimedRegNo(REG_NO)).isTrue();
        assertThat(officeRepository.existsByClaimedRegNoAndIdNot(REG_NO, applicant.getId())).isTrue();
        assertThat(officeRepository.existsByClaimedRegNoAndIdNot(REG_NO, holder.getId())).isFalse();
        assertThat(officeRepository.existsByClaimedRegNo("99999-2026-00003")).isFalse();
    }

    // 대표자가 탈퇴하면 번호가 풀린다 — 같은 번호로 새 사람이 승인받을 수 있어야 한다.
    @Test
    @DisplayName("놓은 번호는 다른 사무소가 잡을 수 있다")
    void released_number_can_be_claimed_again() {
        BrokerageOffice left = office("o-1", REG_NO);
        BrokerageOffice next = office("o-2", REG_NO);
        left.claimRegNo();
        officeRepository.saveAndFlush(left);

        left.releaseRegNo();
        officeRepository.saveAndFlush(left);
        next.claimRegNo();
        officeRepository.saveAndFlush(next);

        assertThat(officeRepository.findById(next.getId()).orElseThrow().getClaimedRegNo()).isEqualTo(REG_NO);
        assertThat(officeRepository.findById(left.getId()).orElseThrow().getClaimedRegNo()).isNull();
    }

    /*
     * ★ 변경 승인은 번호를 옮긴다 — 새 번호를 잡는 것과 옛 번호를 놓는 것이 같은 칸을 덮는 한 번이다(틈이 없다).
     *   옮긴 뒤 옛 번호는 다른 사무소가 잡을 수 있다(대표자 탈퇴 말고 번호가 풀리는 또 하나의 길).
     */
    @Test
    @DisplayName("변경 승인은 새 번호를 잡고 옛 번호를 놓는다 — 옛 번호는 다른 사무소가 잡을 수 있다")
    void approved_change_moves_the_claim() {
        BrokerageOffice moving = office("o-moving", REG_NO);
        BrokerageOffice next = office("o-next", REG_NO);
        moving.claimRegNo();
        officeRepository.saveAndFlush(moving);

        moving.applyApprovedChange("99999-2026-00009", "새상호공인중개사사무소", "홍길동", "서울특별시 동작구",
                null, 66L);
        officeRepository.saveAndFlush(moving);
        next.claimRegNo();
        officeRepository.saveAndFlush(next);

        assertThat(officeRepository.findById(moving.getId()).orElseThrow().getClaimedRegNo())
                .isEqualTo("99999-2026-00009");
        assertThat(officeRepository.findById(next.getId()).orElseThrow().getClaimedRegNo()).isEqualTo(REG_NO);
        assertThat(officeRepository.existsByClaimedRegNoAndIdNot("99999-2026-00009", next.getId())).isTrue();
    }

    // 같은 순간 그 번호를 잡는 다른 승인과 겹친 변경 승인 — 서비스는 이 위반의 제약 이름으로 409 를 고른다.
    @Test
    @DisplayName("다른 사무소가 잡은 번호로 옮기는 변경 승인은 uq_bo_claimed_reg_no 위반으로 올라온다")
    void approved_change_onto_claimed_number_violates_unique() {
        BrokerageOffice holder = office("o-holder", "99999-2026-00009");
        BrokerageOffice moving = office("o-moving", REG_NO);
        holder.claimRegNo();
        moving.claimRegNo();
        officeRepository.saveAndFlush(holder);
        officeRepository.saveAndFlush(moving);

        moving.applyApprovedChange("99999-2026-00009", "새상호공인중개사사무소", "홍길동", "서울특별시 동작구",
                null, 66L);

        assertThatThrownBy(() -> officeRepository.saveAndFlush(moving))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(e.getCause()).isInstanceOfSatisfying(ConstraintViolationException.class,
                        violation -> assertThat(violation.getConstraintName()).isNotNull()
                                .satisfies(name -> assertThat(name.toLowerCase()).contains("uq_bo_claimed_reg_no"))));
    }

    // 정지 이력에 남길 번호 — 잡은 번호가 있으면 그것, 없으면 신청 번호. 네이티브 잠금 읽기가 실제로 도는지까지 본다.
    @Test
    @DisplayName("정지 이력용 번호 읽기는 잡은 번호를, 없으면 신청 번호를 준다")
    void reg_no_for_suspension_prefers_claimed() {
        BrokerageOffice claimed = office("o-claimed", REG_NO);
        claimed.claimRegNo();
        officeRepository.saveAndFlush(claimed);
        BrokerageOffice unclaimed = office("o-unclaimed", "99999-2026-00003");

        assertThat(officeRepository.findRegNoForSuspension(claimed.getId())).contains(REG_NO);
        assertThat(officeRepository.findRegNoForSuspension(unclaimed.getId())).contains("99999-2026-00003");
        assertThat(officeRepository.findRegNoForSuspension(-1L)).isEmpty();
    }

    /*
     * 심사 화면의 "같은 등록번호의 다른 신청". 이전 방식의 재심사로 번호를 고친 채 남은 사무소는 reg_no 가 달라도 옛 번호를 잡고 있다 —
     * 그 사무소 때문에 승인이 409 로 막히므로 함께 나와야 한다. 자기 자신은 빠진다.
     */
    @Test
    @DisplayName("같은 번호의 다른 신청은 같은 번호를 낸 사무소와 그 번호를 잡은 사무소를 함께 준다")
    void others_with_reg_no_include_the_holder() {
        BrokerageOffice self = office("o-self", REG_NO);
        BrokerageOffice sameNumber = office("o-same", REG_NO);
        BrokerageOffice holder = office("o-holder", REG_NO);
        holder.claimRegNo();
        holder.resubmit("99999-2026-00009", holder.getName(), holder.getCeoName(), holder.getAddress(),
                holder.getTel(), null, null);
        officeRepository.saveAndFlush(holder);
        office("o-unrelated", "99999-2026-00003");

        assertThat(officeRepository.findOthersWithRegNo(REG_NO, self.getId()))
                .extracting(BrokerageOffice::getUuid)
                .containsExactly("o-same", "o-holder");
        assertThat(sameNumber.getClaimedRegNo()).isNull();
    }
}
