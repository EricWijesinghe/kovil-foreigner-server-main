package com.kovil.domain.listing.repository;

import com.kovil.domain.listing.entity.BrokerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrokerProfileRepository extends JpaRepository<BrokerProfile, Long> {

    // 한 계정은 한 사무소에만 속한다 — 로그인한 중개사의 사무소를 이걸로 찾는다.
    Optional<BrokerProfile> findByUserId(Long userId);

    // 사무소에 남은 계정이 하나도 없으면 그 사무소 매물은 책임자가 없다.
    List<BrokerProfile> findByOfficeId(Long officeId);

    // 심사 대기열 한 페이지의 사무소를 한 번에 찾는다 — 행마다 찾으면 페이지 크기만큼 쿼리가 는다.
    List<BrokerProfile> findByUserIdIn(Collection<Long> userIds);

    // 심사 화면의 "같은 등록번호의 다른 신청" — 그 사무소들의 대표자를 한 번에 찾는다.
    List<BrokerProfile> findByOfficeIdIn(Collection<Long> officeIds);

    /*
     * 그 계정의 대표자 연결이 가리키는 사무소 — **잠금 읽기**(지금 커밋된 값). 변경 신청 제출 · 변경 승인이 사무소 행을 잠근 뒤에
     * "아직 이 사무소의 대표자인가"를 다시 본다. 일반 읽기는 REPEATABLE READ 에서 트랜잭션 첫 읽기 시점의 스냅숏이라 그사이
     * 커밋된 탈퇴(연결 삭제)를 못 본다. 네이티브 스칼라인 이유는 다른 잠금 읽기와 같다(영속성 컨텍스트의 엔티티와 대조하지 않게).
     */
    @Query(
            value = "SELECT office_id FROM broker_profiles WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Long> findOfficeIdForUpdate(@Param("userId") Long userId);

    // 이탈 판정용 **잠금 읽기**. MySQL 기본 격리(REPEATABLE READ)에서 일반 SELECT 는 트랜잭션 첫 읽기 시점의
    // 스냅숏을 본다 — 사무소 잠금을 기다린 뒤에도 앞사람의 삭제가 안 보여 "남은 사람 있음"으로 읽힌다.
    // 잠금 읽기는 최신 커밋을 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p.id FROM BrokerProfile p WHERE p.officeId = :officeId")
    List<Long> lockIdsByOfficeId(@Param("officeId") Long officeId);
}
