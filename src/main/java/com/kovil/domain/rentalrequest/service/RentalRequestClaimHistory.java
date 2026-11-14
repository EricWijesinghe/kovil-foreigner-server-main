package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.rentalrequest.dto.response.AdminRentalRequestResponse;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import com.kovil.domain.rentalrequest.entity.RentalRequestClaimEvent;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import com.kovil.domain.rentalrequest.repository.RentalRequestClaimEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/*
 * 잡기 이력 — 남기기(잡기 · 놓기 · 대기 복귀 · 종료마다 한 행)와 관리자 상세의 읽기. 남기기는 호출자의 트랜잭션에 합류한다
 * (그 처리가 롤백되면 이력도 남지 않는다).
 *
 * ★★ 잠금: 남기는 INSERT 의 FK 검사는 요청 행과 사무소 행을 S 로 잡는다. 부르는 쪽은 넷 다 그 두 행을 **이미 배타로 쥐고**
 *    부른다(잡기 · 놓기 · 종료는 사무소 → 요청 행, 대기 복귀는 사무소 → 그 사무소의 진행 중 요청 잠금 읽기) — 새로 기다리는
 *    잠금이 생기지 않는다. 행위자(users)에는 FK 가 없어 users 행을 건드리지 않는다(V28 주석). 이 순서를 지키지 않는 곳에서
 *    부르지 않는다 — 사무소 행을 쥐지 않은 채 남기면 사무소 행 S 가 그 트랜잭션의 다른 잠금 뒤에 붙는다.
 */
@Component
@RequiredArgsConstructor
public class RentalRequestClaimHistory {

    private final RentalRequestClaimEventRepository repository;
    private final BrokerageOfficeRepository officeRepository;

    public void claimed(RentalRequest request, Long officeId, Long actorId, LocalDateTime at) {
        repository.save(RentalRequestClaimEvent.claimed(request.getId(), officeId, actorId, at));
    }

    public void released(RentalRequest request, Long officeId, Long actorId, LocalDateTime at) {
        repository.save(RentalRequestClaimEvent.released(request.getId(), officeId, actorId, at));
    }

    public void returned(RentalRequest request, Long officeId, RentalRequestReturnCause cause, LocalDateTime at) {
        repository.save(RentalRequestClaimEvent.returned(request.getId(), officeId, cause, at));
    }

    public void closed(RentalRequest request, Long officeId, Long actorId, RentalRequestOutcome outcome,
                       LocalDateTime at) {
        repository.save(RentalRequestClaimEvent.closed(request.getId(), officeId, actorId, outcome, at));
    }

    /*
     * 관리자 상세의 이력 — 일어난 순서대로. 상호는 그 사무소의 지금 값이다(사무소 행은 지워지지 않는다).
     * 사무소는 한 번에 읽는다 — 사건마다 부르면 잡기 · 놓기가 쌓인 요청에서 왕복이 는다.
     */
    public List<AdminRentalRequestResponse.ClaimEvent> historyOf(Long requestId) {
        List<RentalRequestClaimEvent> events = repository.findByRentalRequestIdOrderByIdAsc(requestId);
        if (events.isEmpty()) {
            return List.of();
        }
        Map<Long, BrokerageOffice> offices = officeRepository
                .findAllById(events.stream().map(RentalRequestClaimEvent::getOfficeId).distinct().toList()).stream()
                .collect(Collectors.toMap(BrokerageOffice::getId, Function.identity()));
        return events.stream()
                .map(event -> AdminRentalRequestResponse.ClaimEvent.of(event, offices.get(event.getOfficeId())))
                .toList();
    }
}
