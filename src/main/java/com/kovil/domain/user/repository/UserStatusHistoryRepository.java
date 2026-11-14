package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.UserStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// append-only. 수정·삭제 메서드를 열지 않는다 — **읽기만 연다.**
public interface UserStatusHistoryRepository extends JpaRepository<UserStatusHistory, Long> {

    /**
     * 한 회원의 상태 전이 전부를 최신순으로.
     *
     * ★ 이 조회가 없으면 원장이 write-only 다. unsuspend 가 users.suspend_reason 을 NULL 로
     *   지우므로, 해제 뒤에는 "왜 정지했었나"를 답할 수 있는 곳이 이 테이블뿐인데 그걸 읽는
     *   경로가 하나도 없었다(적대적 리뷰 2026-09-02). 이력을 쌓기만 하고 안 읽으면 없는 것과 같다.
     */
    List<UserStatusHistory> findByUserIdOrderByIdDesc(Long userId);
}
