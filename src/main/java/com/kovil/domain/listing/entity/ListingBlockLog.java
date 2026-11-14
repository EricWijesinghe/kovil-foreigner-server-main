package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseTimeEntity;
import com.kovil.domain.listing.enums.ListingBlockAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_block_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingBlockLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 10)
    private ListingBlockAction action;

    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    @Column(name = "admin_user_id", nullable = false, updatable = false)
    private Long adminUserId;
}