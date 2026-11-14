package com.kovil.domain.partner.repository;

import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartnerDivisionRepository extends JpaRepository<PartnerDivision, Long> {

    Optional<PartnerDivision> findByUuid(String uuid);

    Optional<PartnerDivision> findByUserIdAndDivision(Long userId, PartnerDivisionType division);

    List<PartnerDivision> findByUserIdOrderByIdAsc(Long userId);

    // 심사 화면의 "같은 등록번호의 다른 신청" — 그 사무소 대표자들의 부문을 한 번에 찾는다.
    List<PartnerDivision> findByUserIdInAndDivision(Collection<Long> userIds, PartnerDivisionType division);

    /*
     * 심사 대기열 — **제출된 것만**(submitted_at 이 있는 것). 가입만 하고 서류를 내지 않은 건은
     * 심사할 것이 없어 관리자 화면을 어지럽히기만 한다.
     * 오래 기다린 제출이 먼저 나온다 — 제출 순서가 곧 처리 순서다.
     */
    @Query("SELECT d FROM PartnerDivision d WHERE d.submittedAt IS NOT NULL "
            + "AND (:status IS NULL OR d.status = :status) "
            + "AND (:division IS NULL OR d.division = :division) "
            + "ORDER BY d.submittedAt ASC, d.id ASC")
    Page<PartnerDivision> findSubmitted(@Param("status") PartnerDivisionStatus status,
                                        @Param("division") PartnerDivisionType division,
                                        Pageable pageable);

    // 화면(/me · 로그인 응답 밖의 조회)이 그리는 승인된 부문. 인가에는 쓰지 않는다 — 인가는 토큰의 클레임이다.
    @Query("SELECT d.division FROM PartnerDivision d WHERE d.userId = :userId "
            + "AND d.status = com.kovil.domain.partner.enums.PartnerDivisionStatus.APPROVED")
    List<PartnerDivisionType> findApprovedTypes(@Param("userId") Long userId);

    /*
     * ★ 토큰에 실을 승인 부문을 **지금 커밋된 값**으로 읽는다(잠금 읽기).
     *
     *   로그인 트랜잭션의 일반 SELECT 는 첫 읽기 시점의 스냅샷을 본다(InnoDB REPEATABLE READ).
     *   발급 경로는 users 행을 잠근 **뒤에** 이걸 부르고, 부문을 바꾸는 쪽(심사)은 같은 users 행을
     *   잠근 **뒤에** 부문을 고친다 — 그래서 둘이 그 행 하나에서 줄을 선다. 그런데 여기서 일반 읽기를
     *   쓰면 줄을 서고도 옛 스냅샷을 읽어, 정지가 커밋된 직후의 로그인이 **취소선보다 늦은 iat 로
     *   승인된 부문을 실은 토큰**을 받아 간다. 잠금 읽기만이 스냅샷을 건너뛰고 최신 커밋을 본다.
     * ★ 네이티브 스칼라다 — 엔티티를 반환하는 잠금 조회는 영속성 컨텍스트의 버전과 대조해 무관한
     *   갱신에도 터진다(UserRepository.findAuthSnapshotForUpdate 와 같은 이유).
     */
    @Query(value = "SELECT division FROM partner_divisions "
            + "WHERE user_id = :userId AND status = 'APPROVED' FOR UPDATE", nativeQuery = true)
    List<String> findApprovedDivisionNamesForUpdate(@Param("userId") Long userId);

    /*
     * 그 사람의 한 부문의 **지금 커밋된** 상태(잠금 읽기). 변경 신청 승인이 사무소 행을 잠근 뒤에 묻는다 — 일반 읽기는
     * 트랜잭션 첫 읽기 시점의 스냅숏이라, 같은 순간 커밋된 정지를 못 보고 정지된 사무소의 번호를 옮길 수 있다.
     * 네이티브 스칼라인 이유는 findApprovedDivisionNamesForUpdate 와 같다.
     */
    @Query(value = "SELECT status FROM partner_divisions "
            + "WHERE user_id = :userId AND division = :division FOR UPDATE", nativeQuery = true)
    Optional<String> findStatusForUpdate(@Param("userId") Long userId, @Param("division") String division);

    /*
     * 그 사람의 모든 부문의 **지금 커밋된** 상태(잠금 읽기). 탈퇴가 "정지된 부문이 있나"를 묻는다 — 탈퇴는 users 행을 잠근 뒤에
     * 부르지만, 일반 읽기는 그 앞 첫 읽기의 스냅숏이라 그사이 커밋된 정지를 못 보고 정지된 사람이 번호를 놓고 떠날 수 있다.
     * 부문을 바꾸는 경로는 모두 그 사람의 users 행을 먼저 잡으므로 이 잠금이 순서를 어기지 않는다.
     */
    @Query(value = "SELECT status FROM partner_divisions WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    List<String> findStatusesForUpdate(@Param("userId") Long userId);
}
