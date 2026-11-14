package com.kovil.domain.listing.entity;

import com.kovil.domain.listing.enums.SellingType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_price_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_type", nullable = false, updatable = false, length = 20)
    private SellingType sellingType;

    @Column(name = "deposit", nullable = false, updatable = false)
    private Long deposit;

    @Column(name = "monthly_rent", nullable = false, updatable = false)
    private Long monthlyRent;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    public boolean samePriceAs(SellingType type, Long newDeposit, Long newMonthlyRent) {
        return sellingType == type
                && Objects.equals(deposit, newDeposit)
                && Objects.equals(monthlyRent, newMonthlyRent);
    }
}
