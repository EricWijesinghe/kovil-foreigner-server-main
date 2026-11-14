package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@Entity
@SuperBuilder
@Table(name = "saved_areas")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedArea extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "name", nullable = false, updatable = false, length = 20)
    private String name;

    @Column(name = "place_label", updatable = false, length = 40)
    private String placeLabel;

    @Column(name = "polygon", nullable = false, updatable = false, length = 2000)
    private String polygon;

    @Column(name = "filters", nullable = false, updatable = false, length = 2000)
    private String filters;

    @PrePersist
    private void assignUuid() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID().toString();
        }
    }
}