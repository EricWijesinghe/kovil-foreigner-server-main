package com.kovil.domain.rentalrequest.service;

import com.kovil.domain.notification.enums.NotiType;
import com.kovil.domain.notification.service.NotificationPublisher;
import com.kovil.domain.rentalrequest.entity.RentalRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/*
 * 유학생에게 가는 임대 요청 알림 셋 — 잡힘 · 종료 · 다시 대기. 호출자의 트랜잭션에 합류한다(NotificationPublisher).
 *
 * ★ 중복 키에 **처리 시각**을 넣는다. 같은 요청이 잡히고 · 놓이고 · 다시 잡힐 수 있다 — 요청 uuid 만으로 키를 만들면
 *   두 번째 잡힘부터 "이미 보낸 알림"으로 버려진다. 담당자 시절의 키(요청 uuid 뿐)와도 겹치지 않아, 그때 처리가 시작됐다가
 *   대기로 되돌아간 요청이 사무소에 잡혀도 알림이 나간다.
 * ★ 딥링크는 유학생 앱의 요청 상세다.
 */
@Component
@RequiredArgsConstructor
public class RentalRequestNotifier {

    private final NotificationPublisher notificationPublisher;

    // 사무소가 잡았다 — {0} 요청 번호 · {1} 사무소 상호. 잡은 시각이 처리 시각이다.
    public void claimed(RentalRequest request, String officeName) {
        publish(request, NotiType.RENTAL_REQUEST_IN_PROGRESS, request.getClaimedAt(),
                Map.of("0", request.getRequestNumber(), "1", Objects.requireNonNull(officeName)));
    }

    // 맡은 사무소가 종료했다 — 결과는 싣지 않는다(유학생 화면은 "종료"만 그린다).
    public void closed(RentalRequest request) {
        publish(request, NotiType.RENTAL_REQUEST_CLOSED, request.getClosedAt(),
                Map.of("0", request.getRequestNumber()));
    }

    /*
     * 다시 대기 — 사무소가 놓았거나 맡을 사람이 사라졌다(대표자 탈퇴 · 부문 정지 · 계정 정지). 이유는 싣지 않는다.
     * 대기로 돌아가면 잡은 시각이 지워지므로 처리 시각을 밖에서 받는다.
     */
    public void released(RentalRequest request, LocalDateTime at) {
        publish(request, NotiType.RENTAL_REQUEST_RELEASED, at, Map.of("0", request.getRequestNumber()));
    }

    private void publish(RentalRequest request, NotiType type, LocalDateTime at, Map<String, Object> params) {
        Objects.requireNonNull(at, "처리 시각 없이 보내면 같은 요청의 다음 알림이 중복으로 버려진다");
        notificationPublisher.publish(request.getUserId(), type,
                type.name() + ":request:" + request.getUuid() + ":" + at,
                params, "/rental-requests/" + request.getUuid());
    }
}
