package com.kovil.domain.rentalrequest.repository;

import com.kovil.domain.rentalrequest.entity.RequestOption;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RequestOptionRepository extends JpaRepository<RequestOption, Long> {

    List<RequestOption> findByActiveTrueOrderBySortOrderAscIdAsc();

    // 비활성 항목도 조회한다 — 기존 요청의 선택을 화면에 유지 표시해야 하기 때문이다.
    List<RequestOption> findByIdIn(Collection<Long> ids);

    List<RequestOption> findByCodeInAndActiveTrue(Collection<String> codes);

    // 질문의 잠금·삭제 판정 축 — 선택형 질문의 답은 **자식 항목 행**을 참조하므로 자식까지 묶는다.
    List<RequestOption> findByParentId(Long parentId);


    // 자동 코드의 다음 번호를 정하는 축 — 같은 접두어를 쓰는 행 수.
    int countByCodeStartingWith(String prefix);

    List<RequestOption> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByCode(String code);

    // 역방향 조건 연쇄 검사(AUDIT) — "이 질문(questionId)의 항목을 조건으로 쓰는 질문이 있는가".
    @Query(
            "SELECT COUNT(o) > 0 FROM RequestOption o "
            + "WHERE o.showWhenOptionId IN (SELECT c.id FROM RequestOption c WHERE c.parentId = :questionId)")
    boolean existsByShowWhenOptionIdParent(
            @Param("questionId") Long questionId);

    // 재정렬 스코프(같은 부모의 형제 전체) 잠금 조회 — 동시 create가 순열 검증을 비껴가지 않게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "SELECT o FROM RequestOption o WHERE (:parentId IS NULL AND o.parentId IS NULL) "
            + "OR (:parentId IS NOT NULL AND o.parentId = :parentId)")
    List<RequestOption> findSiblingsForReorder(
            @Param("parentId") Long parentId);

    // 생성 기본 순서 — 같은 부모 안에서 "새 행은 맨 아래".
    @Query(
            "SELECT COALESCE(MAX(o.sortOrder), 0) FROM RequestOption o "
            + "WHERE (:parentId IS NULL AND o.parentId IS NULL) "
            + "OR (:parentId IS NOT NULL AND o.parentId = :parentId)")
    int maxSortOrderInScope(
            @Param("parentId") Long parentId);
}
