package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.enums.RentalRequestStatus;
import com.kovil.domain.rentalrequest.repository.RentalRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/*
 * 맡을 사람이 사라진 사무소(대표자 탈퇴 · 공인중개사 부문 정지 · 대표자 계정 정지)의
 * 진행 중 요청을 대기로 되돌린다. 요청마다 잡기 이력(원인)과 유학생 알림을 남기며,
 * 호출자의 트랜잭션에 합류한다.
 *
 * - 정지가 풀려도 되찾지 않는다 (그사이 다른 사무소가 잡았을 수 있다).
 * - 락킹 순서: users(호출자) → 사무소 → 요청. 잡기와 같은 방향.
 * - 같은 순간 유학생이 탈퇴하면 MySQL 이 한쪽을 되돌릴 수 있다(409, 재시도하면 된다. 정합성은 유지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RentalRequestPoolReturner {

    private final RentalRequestRepository requestRepository;
    private final RentalRequestNotifier notifier;
    private final RentalRequestClaimHistory claimHistory;
    private final BrokerProfileRepository profileRepository;
    private final BrokerageOfficeRepository officeRepository;

    /*
     * 그 사무소가 쥔 진행 중 요청을 전부 대기로 돌림. 되돌린 건수를 돌려줌. 원인은 잡기 이력에 남음.
     * 사무소 행을 잠근 뒤에 부른다(BrokerageOfficeRepository.lockById · findRegNoForSuspension). 잡기는 사무소 행을 먼저
     * 잠그므로, 그 행을 쥔 채 읽으면 그사이의 잡기를 놓치지 않고 이 처리 중에 새 잡기가 끼지도 않는다.
     */
    public int returnHeldBy(Long officeId, RentalRequestReturnCause cause, LocalDateTime at) {
        List<RentalRequest> held = requestRepository.findByOfficeIdAndStatus(officeId, RentalRequestStatus.IN_PROGRESS);
        for (RentalRequest request : held) {
            request.returnToPool();
            claimHistory.returned(request, officeId, cause, at);
            notifier.released(request, at);
        }
        if (!held.isEmpty()) {
            log.info("[rental-request] 맡을 사람이 사라져 대기로 되돌림 office={} cause={} {}건", officeId, cause, held.size());
        }
        return held.size();
    }

    /*
     * 대표자 계정 정지 — 그 사람이 대표자인 사무소를 찾아(대표자 연결) 그 행을 잠그고 되돌린다. 대표자가 아니면 아무 일도 하지 않는다.
     * 원인은 계정 정지다 — 이 길은 계정 정지만 부른다.
     * ★ 대표자 연결은 잠금 읽기로 본다 — 사무소를 잠그기 전의 일반 읽기는 스냅숏이라 그사이의 탈퇴(연결 삭제)를 못 본다.
     *   연결 → 사무소 순으로 잡는 것은 잡기와 같고, 연결 행을 지우는 탈퇴 · 연결을 다시 보는 변경 신청은 모두 같은 사람의
     *   users 행을 먼저 쥐어 여기와 엇갈리지 않는다.
     */
    public int returnHeldByRepresentative(Long userId, LocalDateTime at) {
        return profileRepository.findOfficeIdForUpdate(userId)
                .map(officeId -> {
                    officeRepository.lockById(officeId);
                    return returnHeldBy(officeId, RentalRequestReturnCause.ACCOUNT_SUSPENDED, at);
                })
                .orElse(0);
    }
}
