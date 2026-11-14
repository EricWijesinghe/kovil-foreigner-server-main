package com.kovil.domain.listing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_description_translations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingDescriptionTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "language_code", nullable = false, updatable = false, length = 10)
    private String languageCode;

    @Column(name = "source_version", nullable = false, updatable = false)
    private int sourceVersion;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "engine", length = 30)
    private String engine;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
