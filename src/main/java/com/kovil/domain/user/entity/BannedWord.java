package com.kovil.domain.user.entity;

import com.kovil.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

// 운영자가 즉시 등록하는 금칙어. 소문자로 저장하고 검사도 normalized 값에 contains로 한다.
// 자동 생성 닉네임의 위생은 이쪽이 아니라 빌드 타임 CI 테스트가 담당한다.
@Getter
@Entity
@SuperBuilder
@Table(name = "banned_words")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BannedWord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "word", nullable = false, length = 50)
    private String word;
}
