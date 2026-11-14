package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.kovil.domain.listing.enums.FixedDetailType;
import com.kovil.domain.listing.enums.MaintenanceChargeType;
import com.kovil.domain.listing.enums.MaintenanceStandardType;


@Getter
@Entity
@SuperBuilder
@Table(name = "listing_maintenance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingMaintenance extends BaseEntity {

    @Id
    @Column(name = "listing_id")
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 20)
    private MaintenanceChargeType chargeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "fixed_detail_type", length = 30)
    private FixedDetailType fixedDetailType;

    @Column(name = "total_cost")
    private Long totalCost;

    @Column(name = "reason_code", length = 4)
    private String reasonCode;

    @Column(name = "reason_etc_desc", length = 255)
    private String reasonEtcDesc;

    @Enumerated(EnumType.STRING)
    @Column(name = "standard_type", length = 30)
    private MaintenanceStandardType standardType;

    @Column(name = "standard_etc_desc", length = 255)
    private String standardEtcDesc;

    public boolean hasItemBreakdown() {
        return chargeType == MaintenanceChargeType.FIXED_FEE
                && fixedDetailType != null && fixedDetailType.hasItemBreakdown();
    }
}
