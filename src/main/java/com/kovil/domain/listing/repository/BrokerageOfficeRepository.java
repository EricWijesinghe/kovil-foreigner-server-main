package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.BrokerageOffice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface BrokerageOfficeRepository extends JpaRepository<BrokerageOffice, Long> {

    Optional<BrokerageOffice> findByUuid(String uuid);

    // 사무소 단위로 중개사 이탈을 줄 세운다 — 같은 사무소의 이탈이 겹치면 늦은 쪽이 앞사람의 커밋을 기다렸다가 센다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o.id FROM BrokerageOffice o WHERE o.id = :id")
    Optional<Long> lockById(@Param("id") Long id);

    /*
     * 그 번호를 **승인받아 쓰고 있는** 사무소가 있는가(claimed_reg_no). 가입은 이걸로 막는다.
     * ★ reg_no 로 세지 않는다 — 미승인 신청끼리는 번호가 겹쳐도 된다(관리자가 한 명만 승인한다).
     *   번호가 막히는 것은 그 번호를 마지막으로 승인받은 사무소가 남아 있는 동안뿐이다(PO 확정 2026-10-06) —
     *   승인 · 정지된 사무소. 번호를 바꾸는 변경 신청이 대기 중이거나 반려돼도 그 사무소는 옛 번호로 영업 중이라 계속 잡는다.
     */
    boolean existsByClaimedRegNo(String claimedRegNo);

    // 제출 · 변경 신청 · 승인 때 — 자기 사무소는 빼고 센다(자기 번호를 그대로 내는 것은 중복이 아니다).
    boolean existsByClaimedRegNoAndIdNot(String claimedRegNo, Long id);

    /*
     * 정지 이력에 남길 번호 — 잡고 있는 번호, 없으면 신청 번호. **사무소 행을 잠그고 지금 커밋된 값**을 읽는다.
     * ★ 일반 읽기는 트랜잭션 첫 읽기 시점의 스냅숏이라, 같은 순간 변경 승인이 번호를 옮겼으면 옛 번호를 이력에 남긴다.
     *   잠금 읽기는 그 커밋을 기다렸다가 최신 값을 읽고, 이 행을 커밋까지 쥐어 변경 승인과 줄을 세운다.
     * ★ 네이티브 스칼라다 — 엔티티를 반환하는 잠금 조회는 영속성 컨텍스트의 버전과 대조해 무관한 갱신에도 터진다.
     */
    @Query(value = "SELECT COALESCE(claimed_reg_no, reg_no) FROM brokerage_offices WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<String> findRegNoForSuspension(@Param("id") Long id);

    /*
     * 심사 화면의 "같은 등록번호의 다른 신청". 신청 번호가 같은 사무소와, 그 번호를 **잡고 있는** 사무소를 함께 준다 —
     * 승인된 사무소는 reg_no 와 잡은 번호가 같지만, 이전 방식의 재심사로 번호를 고친 채 남은 사무소는 reg_no 가 달라도
     * 옛 번호를 잡고 있어 승인을 409 로 막는다. 그 이유가 화면에 보여야 한다.
     */
    @Query("SELECT o FROM BrokerageOffice o WHERE (o.regNo = :regNo OR o.claimedRegNo = :regNo) "
            + "AND o.id <> :excludeId ORDER BY o.id ASC")
    List<BrokerageOffice> findOthersWithRegNo(@Param("regNo") String regNo, @Param("excludeId") Long excludeId);

    /*
     * ★★ 광고를 낼 수 있는 사무소인가 = **그 사무소 대표자의 공인중개사 부문이 APPROVED 이고 대표자 계정이 ACTIVE** 인가.
     *    사무소에는 상태가 없다 — 승인 · 정지의 축은 부문 하나다. 게시 관문이 이걸 묻고, 공개 조회
     *    (ListingRepository.PUBLIC_WHERE · findPublicByUuid)가 **같은 조건**을 EXISTS 안에서 쓴다. 어긋나면 게시는 되는데
     *    목록에 안 나오거나, 그 반대가 된다.
     * ★ 대표자는 broker_profiles 로 찾는다(사무소 : 계정 = 1 : 1, uq_bp_office). 대표자가 탈퇴하면
     *   프로필이 지워져 이 조건이 거짓이 된다 — 관리할 사람 없는 광고는 낼 수 없다.
     * ★ 계정 정지는 부문을 건드리지 않는 처분이라 계정 상태를 따로 본다. 정지가 풀리면 그대로 다시 열린다.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM BrokerProfile p, PartnerDivision d, User u "
            + "WHERE p.officeId = :officeId AND d.userId = p.userId "
            + "AND d.division = com.kovil.domain.partner.enums.PartnerDivisionType.REAL_ESTATE_BROKER "
            + "AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED "
            + "AND u.id = p.userId AND u.deletedAt IS NULL "
            + "AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE")
    boolean isAdvertisable(@Param("officeId") Long officeId);
}
