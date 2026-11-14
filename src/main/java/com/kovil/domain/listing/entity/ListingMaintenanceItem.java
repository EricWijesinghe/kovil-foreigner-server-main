package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.kovil.domain.listing.enums.MaintenanceItemCode;


@Getter
@Entity
@SuperBuilder
@Table(name = "listing_maintenance_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingMaintenanceItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_code", nullable = false, length = 20)
    private MaintenanceItemCode itemCode;

    @Builder.Default
    @Column(name = "is_actual_cost", nullable = false)
    private boolean actualCost = false;

    @Column(name = "cost")
    private Long cost;

    @Column(name = "etc_desc", length = 255)
    private String etcDesc;
}
