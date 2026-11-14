package com.kovil.domain.rentalrequest.entity;

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

@Getter
@Entity
@SuperBuilder
@Table(name = "rental_request_regions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RentalRequestRegion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "rental_request_id", nullable = false)
    private Long rentalRequestId;

    @Column(name = "region_id", nullable = false)
    private Long regionId;

    // ★ 제출 당시 지역명의 박제(V13) — 표시는 스냅샷, 집계는 region_id.
    // 행정구역 개편·오타 수정으로 지역 마스터가 바뀌어도 제출된 요청의 표시는 변하지 않는다.
    @Column(name = "region_name_snapshot", length = 100)
    private String regionNameSnapshot;

    @Column(name = "region_name_snapshot_user", length = 100)
    private String regionNameSnapshotUser;
}
