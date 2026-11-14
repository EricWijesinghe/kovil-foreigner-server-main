package com.kovil.domain.rentalrequest.entity;

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

@Getter
@Entity
@Builder
@Table(name = "request_option_translations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@lombok.AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RequestOptionTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "request_option_id", nullable = false)
    private Long requestOptionId;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    // 질문 밑에 붙는 보조 설명("해당 없다면 다음 질문으로 넘어가주세요"). 없으면 표시하지 않는다.
    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "name", nullable = false, length = 100)
    private String name;
}
