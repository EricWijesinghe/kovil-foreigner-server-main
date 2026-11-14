package com.kovil.domain.university.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Getter
@Entity
@SuperBuilder
@Table(name = "universities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class University extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    @Column(name = "name_en", nullable = false, length = 200)
    private String nameEn;

    @Column(name = "short_name_ko", length = 30)
    private String shortNameKo;

    @Column(name = "short_name_en", length = 40)
    private String shortNameEn;

    @Column(name = "campus_name_ko", length = 50)
    private String campusNameKo;

    @Column(name = "campus_name_en", length = 100)
    private String campusNameEn;

    @Column(name = "address_road", nullable = false)
    private String addressRoad;

    @Column(name = "lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal lng;

    @Column(name = "region_id")
    private Long regionId;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}