package com.kovil.domain.listing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_favorites")
@IdClass(ListingFavorite.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingFavorite {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "listing_id")
    private Long listingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public record Key(Long userId, Long listingId) implements Serializable {
        public Key() { this(null, null); }
    }
}
