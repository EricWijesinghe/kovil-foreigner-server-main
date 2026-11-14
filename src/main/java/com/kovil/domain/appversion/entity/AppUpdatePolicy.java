package com.kovil.domain.appversion.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.appversion.enums.AppKind;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@SuperBuilder
@Table(name = "app_update_policies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUpdatePolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "app", nullable = false, length = 16)
    private AppKind app;

    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "min_supported_version", nullable = false, length = 32)
    private String minSupportedVersion;

    @Column(name = "latest_version", nullable = false, length = 32)
    private String latestVersion;

    @Column(name = "store_url", nullable = false, length = 512)
    private String storeUrl;

    public void update(String minSupportedVersion, String latestVersion, String storeUrl) {
        this.minSupportedVersion = minSupportedVersion;
        this.latestVersion = latestVersion;
        this.storeUrl = storeUrl;
    }
}
