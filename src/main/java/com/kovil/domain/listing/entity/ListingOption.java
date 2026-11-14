package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serializable;

@Getter
@Entity
@SuperBuilder
@Table(name = "listing_options")
@IdClass(ListingOption.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ListingOption extends BaseEntity {

    @Id
    @Column(name = "listing_id")
    private Long listingId;

    @Id
    @Column(name = "code", length = 40)
    private String code;

    public record Key(Long listingId, String code) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
