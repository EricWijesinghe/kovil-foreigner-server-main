package com.kovil.domain.rentalrequest.entity;

import com.kovil.domain.rentalrequest.enums.RentalRequestClaimEventType;
import com.kovil.domain.rentalrequest.enums.RentalRequestOutcome;
import com.kovil.domain.rentalrequest.enums.RentalRequestReturnCause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;


@Getter
@Entity
@Table(name = "rental_request_claim_events", indexes = {
        @Index(name = "idx_rrce_request", columnList = "rental_request_id, id"),
        @Index(name = "idx_rrce_office", columnList = "office_id, id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class RentalRequestClaimEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "rental_request_id", nullable = false, updatable = false)
    private Long rentalRequestId;

    @Column(name = "office_id", nullable = false, updatable = false)
    private Long officeId;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 20)
    private RentalRequestClaimEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_cause", updatable = false, length = 30)
    private RentalRequestReturnCause returnCause;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", updatable = false, length = 20)
    private RentalRequestOutcome outcome;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public static RentalRequestClaimEvent claimed(Long requestId, Long officeId, Long actorId, LocalDateTime at) {
        return of(requestId, officeId, Objects.requireNonNull(actorId), RentalRequestClaimEventType.CLAIMED,
                null, null, at);
    }

    public static RentalRequestClaimEvent released(Long requestId, Long officeId, Long actorId, LocalDateTime at) {
        return of(requestId, officeId, Objects.requireNonNull(actorId), RentalRequestClaimEventType.RELEASED,
                null, null, at);
    }

    public static RentalRequestClaimEvent returned(Long requestId, Long officeId, RentalRequestReturnCause cause,
                                                   LocalDateTime at) {
        return of(requestId, officeId, null, RentalRequestClaimEventType.RETURNED,
                Objects.requireNonNull(cause), null, at);
    }

    public static RentalRequestClaimEvent closed(Long requestId, Long officeId, Long actorId,
                                                 RentalRequestOutcome outcome, LocalDateTime at) {
        return of(requestId, officeId, Objects.requireNonNull(actorId), RentalRequestClaimEventType.CLOSED,
                null, Objects.requireNonNull(outcome), at);
    }

    private static RentalRequestClaimEvent of(Long requestId, Long officeId, Long actorId,
                                              RentalRequestClaimEventType type, RentalRequestReturnCause cause,
                                              RentalRequestOutcome outcome, LocalDateTime at) {
        return RentalRequestClaimEvent.builder()
                .rentalRequestId(Objects.requireNonNull(requestId))
                .officeId(Objects.requireNonNull(officeId))
                .actorId(actorId)
                .eventType(type)
                .returnCause(cause)
                .outcome(outcome)
                .occurredAt(Objects.requireNonNull(at))
                .build();
    }
}
