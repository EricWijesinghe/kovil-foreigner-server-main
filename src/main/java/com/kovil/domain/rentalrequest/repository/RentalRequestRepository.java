package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RentalRequestRepository extends JpaRepository<RentalRequest, Long> {

    Optional<RentalRequest> findByUuid(String uuid);

    /*
     * 요청을 낸 사람 — 잡기 · 놓기 · 종료가 요청 행을 잠그기 **전에** 그 사람의 users 행부터 잡으려고 먼저 묻는다(서비스 주석).
     * 일반 읽기(스냅숏)여도 된다 — 신청자는 바뀌지 않는다. 엔티티를 싣지 않는 스칼라라 뒤의 잠금 읽기가 영속성 컨텍스트의
     * 옛 판과 부딪히지 않는다.
     */
    @Query("SELECT r.userId FROM RentalRequest r WHERE r.uuid = :uuid")
    Optional<Long> findUserIdByUuid(@Param("uuid") String uuid);

    /*
     * 사무소가 잡기 · 놓기 · 종료할 요청 — **잠금 읽기**(지금 커밋된 값 + 행 배타 잠금).
     * ★ 같은 요청을 동시에 잡은 두 사무소는 여기서 줄을 선다. 늦은 쪽은 앞사람의 커밋을 기다렸다가 "진행 중"을 읽고
     *   RENTAL_REQUEST_ALREADY_CLAIMED 로 진다 — 취소된 요청이면 REQUEST_NOT_OPEN 이다(같은 순간의 유학생 취소도 가린다).
     *   @Version 은 그 뒤의 마지막 방어선으로 남는다.
     * ★ 이 요청이 이미 영속성 컨텍스트에 있으면 안 된다 — 엔티티 잠금 조회는 들고 있던 판과 버전을 대조해 터진다.
     *   그래서 신청자는 위의 스칼라로 묻는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RentalRequest r WHERE r.uuid = :uuid")
    Optional<RentalRequest> findByUuidForUpdate(@Param("uuid") String uuid);

    Page<RentalRequest> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    // 탈퇴 정리용. 페이지가 아니라 전건이어야 한다 — 열린 건을 하나라도 남기면
    // 사무소들의 대기 목록에 연락 닿지 않는 행이 그대로 떠 있는다.
    // 락킹 조회. 탈퇴의 스냅숏은 users 행을 잠그기 전(관문 조회)에 고정되는데, 그사이 사무소가 이 요청을 잡아 커밋하면
    // 일반 읽기는 옛 버전(대기)을 들고 취소해 @Version 에 걸리고 탈퇴가 409 로 실패한다(실 MySQL 재현). 잠금 읽기는 최신 커밋을 읽는다.
    // 잠금 순서는 탈퇴의 users → 요청 행이고, 잡기도 신청자 users 행을 요청 행보다 먼저 잡아 같은 방향.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RentalRequest> findByUserIdAndStatusIn(Long userId, Collection<RentalRequestStatus> statuses);

    long countByUserIdAndStatusIn(Long userId, Collection<RentalRequestStatus> statuses);

    // FR-309 상한 검사는 일반 count 로 충분하다 — 단, **유저 잠금이 트랜잭션의 첫 읽기**라는
    // 전제 위에서다(UserRepository.findIdByUuidForUpdate 주석 참조). 여기서 FOR UPDATE 를
    // 걸었던 시도는 빈 결과에도 갭락을 잡아, 열린 요청 0건인 서로 다른 유저의 첫 제출끼리
    // 데드락을 만들었다(적대적 리뷰 2026-09-02). 부활 금지.

    boolean existsByRequestNumber(String requestNumber);

    /*
     * 사무소들이 보는 대기 목록의 조건 — **대기 중이고 신청자 계정이 살아 있는(ACTIVE · 미탈퇴)** 요청.
     * ★ 정지된 유학생의 요청은 목록에서 빠진다(연락이 닿지 않는 사람을 잡게 하지 않는다). 정지가 풀리면 그대로 돌아온다 —
     *   요청은 건드리지 않는다. 탈퇴는 열린 요청을 취소하므로 여기 올 일이 없지만, 정리 전 순간을 위해 함께 본다.
     * ★ 승인된 모든 사무소가 전국의 대기 요청을 본다(PO 확정 2026-10-07) — 사무소 지역으로 거르지 않는다.
     *   지역은 앱이 고른 희망 지역으로만 좁힌다(아래 InRegions).
     */
    String POOL_WHERE = """
            FROM RentalRequest r
            WHERE r.status = com.kovil.domain.rentalrequest.enums.RentalRequestStatus.SUBMITTED
              AND EXISTS (SELECT u.id FROM User u
                          WHERE u.id = r.userId AND u.deletedAt IS NULL
                            AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE)
            """;

    /*
     * 희망 지역 조건 — 요청의 희망 지역이 **고른 시군구 중 하나라도** 걸리면 나온다.
     * ★ 희망 지역은 두 축에 있다. 질문지 이관(V14) 전 요청은 고정 지역(rental_request_regions)에, 그 뒤 요청은
     *   질문지의 지역 답(rental_request_options.region_id — ADDRESS 질문의 답만 이 칸을 채운다)에 있다.
     *   한쪽만 보면 옛 요청이나 새 요청이 통째로 목록에서 사라진다.
     * ★ 두 축 모두 시군구 행만 담는다(시도는 서비스가 거절한다). 시도로 거르고 싶으면 호출부가 그 시도의 시군구 id 로 넓혀 넘긴다.
     */
    String IN_REGIONS = """
              AND (EXISTS (SELECT g.id FROM RentalRequestRegion g
                           WHERE g.rentalRequestId = r.id AND g.regionId IN :regionIds)
                   OR EXISTS (SELECT o.id FROM RentalRequestOption o
                              WHERE o.rentalRequestId = r.id AND o.regionId IN :regionIds))
            """;

    // 대기 목록 — **오래 기다린 것부터**(신청 순서가 곧 처리 순서다). 잡기 전 요약만 내보내는 것은 응답 쪽 몫이다.
    @Query(value = "SELECT r " + POOL_WHERE + " ORDER BY r.id ASC",
            countQuery = "SELECT COUNT(r) " + POOL_WHERE)
    Page<RentalRequest> findPool(Pageable pageable);

    // 희망 지역으로 좁힌 대기 목록. 빈 목록을 넘기지 않는다 — 고른 지역이 없으면 findPool 을 부른다.
    @Query(value = "SELECT r " + POOL_WHERE + IN_REGIONS + " ORDER BY r.id ASC",
            countQuery = "SELECT COUNT(r) " + POOL_WHERE + IN_REGIONS)
    Page<RentalRequest> findPoolInRegions(@Param("regionIds") Collection<Long> regionIds, Pageable pageable);

    /*
     * 사무소가 맡은(맡았던) 요청 — 진행 중 · 종료 · 맡은 뒤 유학생이 취소한 것. 놓았거나 대기로 돌아간 요청은 office_id 가
     * 비워져 여기 나오지 않는다(더는 그 사무소의 것이 아니다). status 가 null 이면 전부, 최신순.
     */
    @Query("SELECT r FROM RentalRequest r WHERE r.officeId = :officeId "
            + "AND (:status IS NULL OR r.status = :status) ORDER BY r.id DESC")
    Page<RentalRequest> findHandledByOffice(@Param("officeId") Long officeId,
                                            @Param("status") RentalRequestStatus status, Pageable pageable);

    /*
     * 사무소별 진행 중 개수 — 잡기 상한(설정값)의 판정 축.
     * ★ 일반 count 다 — **사무소 행 잠금(BrokerageOfficeRepository.lockById)이 트랜잭션의 어떤 일반 읽기보다도 먼저**여야
     *   맞는 값을 센다. 잠금 읽기는 읽기 뷰를 만들지 않아, 잠금을 기다린 뒤의 첫 일반 읽기가 앞사람의 커밋을 본다. 잠금 전에
     *   일반 조회가 하나라도 있으면(요청 · 대표자 연결을 먼저 읽는 등) 스냅숏이 그때 고정돼 같은 사무소의 동시 잡기가 상한을
     *   넘긴다 — 사무소 id 가 필요하면 잠금 읽기(BrokerProfileRepository.findOfficeIdForUpdate)로 찾는다.
     *   잠금 순서상 사무소보다 앞에 오는 users 행의 **잠금 읽기**는 그 앞에 와도 된다(읽기 뷰를 만들지 않는다).
     * ★ 이 count 에 FOR UPDATE 를 걸지 않는다. 진행 중 0건인 서로 다른 두 사무소가 같은 갭을 잠근 뒤 각자 요청을 그 갭으로
     *   옮기며 교착한다(유저의 열린 요청 상한에서 실제로 겪은 일 — 위 주석). 같은 사무소의 직렬화는 사무소 행 잠금이 한다.
     */
    long countByOfficeIdAndStatus(Long officeId, RentalRequestStatus status);

    /*
     * 맡을 사람이 사라졌을 때(대표자 탈퇴 · 부문 정지 · 계정 정지) 대기로 되돌릴 요청 — 페이지가 아니라 전건이다.
     * 하나라도 남기면 유학생은 아무도 연락하지 않는 "진행 중"을 하염없이 기다린다.
     * ★ **잠금 읽기**다. 그 처분들은 사무소 행을 잠그기 전에 일반 조회를 해 스냅숏이 이미 고정돼 있다 — 일반 읽기면 그사이
     *   커밋된 잡기를 못 보고, 정지된 사무소가 그 요청을 계속 쥔다. 잠금 읽기는 최신 커밋을 읽는다(잡기는 사무소 행을 먼저
     *   잠그므로, 사무소 행을 쥔 뒤의 이 읽기가 놓치는 잡기는 없다).
     * ★ 반복 읽기(기본 격리)에서는 그 사무소 구간에 갭 잠금도 걸린다. 잡기 · 놓기 · 종료 · 유학생 수정은 범위 잠금을 하지 않아
     *   여기서 기다릴 뿐 맞물리지 않는다(같은 사무소의 잡기 · 놓기 · 종료는 그보다 먼저 사무소 행에서 줄을 선다).
     * ⚠ 유학생 취소 · 탈퇴(상태 변경)와는 맞물릴 수 있다. 이 읽기는 (사무소, 상태) 보조 인덱스 항목 → 기본 키 순으로 잡고, 그 UPDATE 는
     *   기본 키 → 보조 인덱스 항목(옛 항목 지우기 표시 · 새 항목) 순으로 잡는다. 한 문장 안의 좁은 창이라 드물고, 지면 409 · 재시도다
     *   (RentalRequestPoolReturner 주석의 알려진 것).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RentalRequest> findByOfficeIdAndStatus(Long officeId, RentalRequestStatus status);

    /*
     * 관리자 열람(읽기 전용)의 목록 — 상태 · 맡은 사무소 · 검색어. **최신순**이다 — 관리자는 처리하지 않고 지켜본다
     * (처리 순서인 "오래된 것부터"는 사무소들의 대기 목록 몫이다).
     * ★ status · officeId 가 null 이면 그 조건을 걸지 않는다(전체).
     * ★ query 는 요청번호 또는 신청자 이름의 부분일치다. 대소문자는 무시한다.
     *   이름은 **두 축을 모두** 본다. users.name 은 선택 입력이라 소셜 가입자는 NULL 이고
     *   탈퇴하면 지워지는데, 유학생이 문의하며 대는 이름은 여권 성명(foreigner_verifications.full_name,
     *   NOT NULL)인 경우가 많다. 한쪽만 보면 **들은 이름을 그대로 쳤는데 0건**이 된다.
     */
    @Query("SELECT r FROM RentalRequest r "
            + "WHERE (:status IS NULL OR r.status = :status) "
            + "AND (:officeId IS NULL OR r.officeId = :officeId) "
            + "AND (:query IS NULL "
            + "     OR LOWER(r.requestNumber) LIKE LOWER(CONCAT('%', :query, '%')) "
            + "     OR EXISTS (SELECT 1 FROM User u WHERE u.id = r.userId "
            + "                AND LOWER(u.name) LIKE LOWER(CONCAT('%', :query, '%'))) "
            + "     OR EXISTS (SELECT 1 FROM ForeignerVerification v WHERE v.id = r.verificationId "
            + "                AND LOWER(v.fullName) LIKE LOWER(CONCAT('%', :query, '%')))) "
            + "ORDER BY r.id DESC")
    Page<RentalRequest> searchForAdmin(@Param("status") RentalRequestStatus status,
                                       @Param("officeId") Long officeId,
                                       @Param("query") String query,
                                       Pageable pageable);
}
