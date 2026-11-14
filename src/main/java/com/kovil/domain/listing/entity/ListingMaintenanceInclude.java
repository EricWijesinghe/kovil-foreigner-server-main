package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import com.kovil.domain.listing.enums.MaintenanceItemCode;

import java.io.Serializable;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_maintenance_includes")
@IdClass(ListingMaintenanceInclude.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingMaintenanceInclude extends BaseEntity {

    @Id
    @Column(name = "listing_id")
    private Long listingId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "item_code", length = 20)
    private MaintenanceItemCode itemCode;

    public record Key(Long listingId, MaintenanceItemCode itemCode) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
