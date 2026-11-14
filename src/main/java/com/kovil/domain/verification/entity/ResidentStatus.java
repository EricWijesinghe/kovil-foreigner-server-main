package com.kovil.domain.verification.entity;

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

@Getter
@Entity
@SuperBuilder
@Table(name = "resident_statuses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResidentStatus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // 기계 축이다. 코드가 이 값으로 분기하면 설계 위반이다(INV-03).
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    // 신분이 늘어도 앱 배포를 하지 않기 위한 축 — FE가 키로 아이콘을 고른다.
    @Column(name = "icon_key", length = 40)
    private String iconKey;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;


    public void update(String iconKey, Integer sortOrder, Boolean active) {
        this.iconKey = iconKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    // /order 재정렬 전용 — 이름·활성은 건드리지 않는다.
    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
