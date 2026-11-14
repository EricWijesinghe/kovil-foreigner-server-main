package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 마지막 관리자 강등 차단의 판정값. 탈퇴자는 세지 않는다 — 들어올 수 없는 사람이다.
     *
     * <p>★ <b>쓸 수 있는</b> 관리자만 센다(ACTIVE). 정지된 관리자를 세면 정지 → 탈퇴 두 수로
     * 관리자가 0명이 된다 — 가드는 2명이라 보고 통과시키는데 실제로 들어갈 수 있는 사람은
     * 없다(적대적 리뷰 2026-09-17 2라운드).
     *
     * <p>★ 잠금판이어야 한다. 일반 count 로 보면 두 트랜잭션이 <b>각자 2를 읽고 둘 다 통과해</b>
     * 관리자가 0명이 된다(서로 다른 관리자를 동시에 강등하는 경우). 그 상태를 되돌릴 화면이
     * 이 제품에 없어 DB 를 직접 고쳐야 한다. 관리자 행 전체를 잠가 직렬화하면 늦은 쪽이
     * 갱신된 수를 읽고 정직하게 막힌다. 관리자는 몇 명 되지 않아 잠금 비용도 작다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT u.id FROM User u WHERE u.role = :role AND u.deletedAt IS NULL "
            + "AND u.status = com.kovil.domain.user.enums.UserStatus.ACTIVE ORDER BY u.id")
    List<Long> findIdsByRoleForUpdate(
            @Param("role")
            UserRole role);

    // FR-907 — 회원 검색. 기본은 탈퇴자를 뺀다(이력은 남지만 관리 대상이 아니다).
    // ★ 단, status=WITHDRAWN 을 **명시적으로 고르면 보여준다.** 탈퇴는 status 와 deleted_at 을
    //   함께 찍으므로 deleted_at IS NULL 을 하드 필터하면 그 선택지가 언제나 빈 페이지가 된다
    //   — 관리자 화면의 "탈퇴" 드롭다운이 영원히 아무것도 못 찾았다(적대적 리뷰 2026-09-02).
    // query 와 phoneHash 는 호출부가 둘 중 하나만 채운다 — 전화번호는 암호문+HMAC 라
    // 부분검색이 없고, 검색어가 전체 번호로 정규화될 때만 해시 완전일치로 들어온다.
    @Query(
            "SELECT u FROM User u WHERE (u.deletedAt IS NULL OR u.status = :status) "
            + "AND (:status IS NULL OR u.status = :status) "
            + "AND (:role IS NULL OR u.role = :role) "
            + "AND (:phoneHash IS NULL OR u.phoneHash = :phoneHash) "
            + "AND (:query IS NULL OR u.email LIKE CONCAT('%', :query, '%') "
            + "     OR u.name LIKE CONCAT('%', :query, '%') "
            + "     OR u.nickname LIKE CONCAT('%', :query, '%')) "
            + "ORDER BY u.id DESC")
    Page<User> searchForAdmin(
            @Param("status") UserStatus status,
            @Param("role") UserRole role,
            @Param("query") String query,
            @Param("phoneHash") String phoneHash,
            Pageable pageable);

    Optional<User> findByPhoneHash(String phoneHash);

    Optional<User> findByUuidAndDeletedAtIsNull(String uuid);

    // 관리자 읽기 전용 — 탈퇴자도 찾는다. 변경 경로는 위(deletedAt IS NULL)를 쓴다.
    Optional<User> findByUuid(String uuid);

    boolean existsByNicknameNormalized(String nicknameNormalized);
    // 임대요청 제출 직렬화 전용(AUDIT_2026_08_26) — FR-309 활성 상한이 count-후-INSERT라
    // 2연타 동시 제출이 상한을 뚫었다(DB 유니크는 PO 미확정으로 부재). 유저 행 잠금으로
    // 같은 유저의 제출을 한 줄로 세운다 — 서로 다른 유저는 서로 막지 않는다.
    //
    // ★ 반드시 **id 만** 고르는 네이티브 FOR UPDATE 여야 한다. 엔티티를 반환하는 잠금 조회는
    //   영속성 컨텍스트의 User(@Version)와 최신 행의 version 을 대조해, 무관한 users 갱신이
    //   끼기만 해도 StaleObjectStateException → 409 로 제출이 죽는다(적대적 리뷰 2026-09-02).
    //
    // ★ uuid 로 받는 이유 — **트랜잭션의 첫 읽기여야 하기 때문이다.** InnoDB 의 읽기 뷰는
    //   첫 일반 SELECT 에서 생성되고 잠금 읽기는 뷰를 만들지 않는다. 이 잠금이 첫 문장이면
    //   이후의 일반 count 가 경쟁자 커밋 **이후** 스냅샷을 받아 최신 커밋을 본다 —
    //   rental_requests 쪽에 FOR UPDATE 를 걸 필요가 없어진다(그쪽 갭락은 열린 요청 0건인
    //   서로 다른 유저끼리 데드락을 만들었다, 적대적 리뷰 2026-09-02). id 는 아직 모르는
    //   시점이라 uuid 가 잠금 키다. deleted_at 필터로 탈퇴자는 잠그지 않는다.
    @Query(
            value = "SELECT id FROM users WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE",
            nativeQuery = true)
    Optional<Long> findIdByUuidForUpdate(
            @Param("uuid") String uuid);

    // ★ 토큰에 실을 역할을 **지금 커밋된 값**으로 읽는다.
    //
    //   엔티티의 role 은 트랜잭션 첫 읽기 시점의 스냅샷이다(InnoDB REPEATABLE READ). 로그인은
    //   그 뒤 비밀번호 해시 검증에 수백 ms 를 쓰는데, 그 사이 강등이 커밋돼도 엔티티는 옛 역할
    //   그대로라 **강등된 사람이 옛 권한 토큰을 받아 간다**. 액세스 토큰 취소선(Redis)은 이미
    //   그어진 뒤라 iat 가 취소선보다 늦어 그 한 장이 그대로 통한다(적대적 리뷰 2026-09-17 3라운드).
    //   잠금 읽기만이 스냅샷을 무시하고 최신 커밋을 본다 — 취소하는 쪽도 같은 행을 잠그므로
    //   발급과 취소가 이 행 하나에서 줄을 선다. 어느 쪽이 먼저든 결과가 옳다:
    //   발급이 먼저면 취소선이 그 토큰보다 뒤에 그어져 죽이고, 취소가 먼저면 새 역할로 발급된다.
    //
    // ★ 엔티티가 아니라 네이티브 스칼라여야 한다. 엔티티를 반환하는 잠금 조회는 영속성 컨텍스트의
    //   User(@Version)와 최신 행의 version 을 대조해 무관한 users 갱신만으로도 터진다(위 참조).
    //   deleted_at 을 거르지 않는 이유: 탈퇴 처리도 이 조회로 취소선을 긋기 때문이다 —
    //   이미 지워진 사람이라고 건너뛰면 그 사람의 액세스 토큰이 살아남는다. 탈퇴자 차단은
    //   status 로 한다.
    @Query(
            value = "SELECT uuid AS uuid, role AS role, status AS status "
                  + "FROM users WHERE id = :id FOR UPDATE",
            nativeQuery = true)
    Optional<AuthSnapshot> findAuthSnapshotForUpdate(
            @Param("id") Long id);

    // 매물 등록이 INSERT 전에 작성자 행을 **공유 잠금**으로 먼저 잡는다(BrokerListingService.create).
    //
    // ★ INSERT 의 FK 검사도 잠금이다. InnoDB 는 인덱스 순서대로 부모 행을 S 로 잡는데, listings 는 사무소
    //   인덱스(idx_ls_office)가 작성자 FK 의 자동 인덱스보다 앞이라 그대로 두면 사무소 → users 순이 된다.
    //   users → 사무소로 잡는 변경 승인 · 변경 신청 제출 · 탈퇴 · 정지와 엇갈려, 서로의 행을 쥔 채 기다리다
    //   한쪽이 1213 으로 진다(MySQL 9.5 재현). users 를 먼저 잡으면 같은 파트너의 그 경로들과 이 행에서 줄을 선다.
    //
    // ★ 배타가 아니라 공유여야 한다. 배타로 잡으면 지역 행을 쥔 채 그 지역 중개사에게 알림을 넣는 지역 닫힘
    //   (알림 INSERT 의 FK 검사가 users S)과 교착한다 — 매물 INSERT 는 지역 S 를, 지역 닫힘은 users 를 기다린다
    //   (MySQL 9.5 재현). 공유끼리는 서로 막지 않고, users 를 배타로 잡는 경로와만 줄을 선다.
    //
    // ★ id(기본 키)로 잡는다. FK 검사가 잡는 것은 부모의 PK 행이다. uuid 로 공유 잠금을 걸면 InnoDB 는
    //   커버링 인덱스(uq_users_uuid)에서 읽기를 끝내 **PK 행을 잠그지 않는다** — FOR UPDATE 와 달리 FOR SHARE 는
    //   그렇다(MySQL 9.5 실측: uq_users_uuid S 만 잡히고 교착은 그대로 났다).
    //
    // ★ JPQL + PESSIMISTIC_READ 로 둔다 — 방언이 MySQL 에서는 FOR SHARE, H2 에서는 FOR UPDATE 로 낸다
    //   (H2 에는 FOR SHARE 문법이 없어 네이티브로 쓰면 테스트가 깨진다). id 만 골라 엔티티 버전 대조가 없다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT u.id FROM User u WHERE u.id = :id")
    Optional<Long> lockSharedById(
            @Param("id") Long id);

    // 사무소가 잡기 · 놓기 · 종료하는 요청의 **신청자** 상태 — 공유 잠금으로 지금 커밋된 값을 읽는다(PartnerRentalRequestService).
    //
    // ★ 요청 행을 잠그기 **전에** 잡는다. 뒤따르는 알림 INSERT 의 FK 검사가 어차피 이 행을 S 로 잡는데, 그때는 요청 행을
    //   쥔 뒤라 신청자의 탈퇴(users → 요청 행 순서)와 서로의 행을 쥔 채 기다리게 된다. 먼저 잡으면 탈퇴와 이 행에서 줄을 선다 —
    //   탈퇴의 요청 취소가 사무소 행을 잡지 않는다는 전제에서다(PartnerRentalRequestService 클래스 주석의 FK 인덱스).
    // ★ 공유 잠금이 lockSharedById 와 같은 모양인 이유(id 로 · JPQL + PESSIMISTIC_READ)는 그 주석에 있다.
    //   탈퇴한 계정은 비어 나온다(deleted_at).
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query(
            "SELECT u.status FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<UserStatus> findStatusByIdForShare(
            @Param("id") Long id);

    interface AuthSnapshot {
        String getUuid();
        String getRole();
        String getStatus();
    }
}
