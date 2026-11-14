package com.kovil.domain.country.entity;

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

// 번역 오버레이는 감사 시각을 갖지 않는다(V1에 created_at/updated_at 컬럼이 없다).
// 베이스 엔티티를 붙이면 스키마와 어긋난다.
@Getter
@Entity
@Builder
@Table(name = "country_translations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@lombok.AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CountryTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "country_id", nullable = false)
    private Long countryId;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;
}
