package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Entity
@SuperBuilder
@Table(name = "brokerage_offices",
        uniqueConstraints = @UniqueConstraint(name = "uq_bo_claimed_reg_no", columnNames = "claimed_reg_no"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerageOffice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "reg_no", nullable = false, length = 20)
    private String regNo;

    @Column(name = "claimed_reg_no", length = 20)
    private String claimedRegNo;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "ceo_name", nullable = false, length = 50)
    private String ceoName;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    @Column(name = "tel", nullable = false, length = 30)
    private String tel;

    @Column(name = "biz_no", length = 12)
    private String bizNo;

    @Column(name = "registry_status", length = 20)
    private String registryStatus;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "license_file_id")
    private Long licenseFileId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void resubmit(String regNo, String name, String ceoName, String address, String tel,
                         String bizNo, Long licenseFileId) {
        this.regNo = regNo;
        this.name = name;
        this.ceoName = ceoName;
        this.address = address;
        this.tel = tel;
        this.bizNo = bizNo;
        this.licenseFileId = licenseFileId;
    }

    public boolean isSameForReview(String regNo, String name, String ceoName, String address, String bizNo,
                                   Long licenseFileId) {
        return Objects.equals(this.regNo, regNo)
                && Objects.equals(this.name, name)
                && Objects.equals(this.ceoName, ceoName)
                && Objects.equals(this.address, address)
                && Objects.equals(this.bizNo, bizNo)
                && Objects.equals(this.licenseFileId, licenseFileId);
    }

    public void changeTel(String tel) {
        this.tel = tel;
    }

    public void claimRegNo() {
        this.claimedRegNo = this.regNo;
    }

    public void applyApprovedChange(String regNo, String name, String ceoName, String address, String bizNo,
                                    Long licenseFileId) {
        this.regNo = regNo;
        this.claimedRegNo = regNo;
        this.name = name;
        this.ceoName = ceoName;
        this.address = address;
        this.bizNo = bizNo;
        this.licenseFileId = licenseFileId;
    }

    public void releaseRegNo() {
        this.claimedRegNo = null;
    }
}
