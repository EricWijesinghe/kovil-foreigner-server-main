package com.kovil.domain.listing.entity;

import com.kovil.common.entity.BaseEntity;
import com.kovil.domain.listing.enums.AvailabilityAnswer;
import com.kovil.domain.listing.enums.Direction;
import com.kovil.domain.listing.enums.DirectionBase;
import com.kovil.domain.listing.enums.EntranceType;
import com.kovil.domain.listing.enums.FloorBand;
import com.kovil.domain.listing.enums.FloorType;
import com.kovil.domain.listing.enums.HeatingType;
import com.kovil.domain.listing.enums.ListingCategory;
import com.kovil.domain.listing.enums.ListingStatus;
import com.kovil.domain.listing.enums.RoomType;
import com.kovil.domain.listing.enums.SellingType;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.kovil.domain.listing.exception.ListingErrorCode;
import com.kovil.domain.listing.exception.ListingException;
import com.kovil.domain.listing.dto.request.BrokerListingSaveRequest;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Entity
@SuperBuilder
@Table(name = "listings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listing extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private String uuid;

    @Column(name = "listing_no", nullable = false, updatable = false, length = 20)
    private String listingNo;

    @Column(name = "office_id", nullable = false, updatable = false)
    private Long officeId;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private Long createdByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ListingCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 20)
    private RoomType roomType;

    @Builder.Default
    @Column(name = "is_division", nullable = false)
    private boolean division = false;

    @Builder.Default
    @Column(name = "is_duplex", nullable = false)
    private boolean duplex = false;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "description_version", nullable = false)
    private int descriptionVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "selling_type", nullable = false, length = 20)
    private SellingType sellingType;

    @Column(name = "deposit", nullable = false)
    private Long deposit;

    @Builder.Default
    @Column(name = "monthly_rent", nullable = false)
    private Long monthlyRent = 0L;

    @Column(name = "exclusive_area", nullable = false, precision = 10, scale = 2)
    private BigDecimal exclusiveArea;

    @Column(name = "supply_area", precision = 10, scale = 2)
    private BigDecimal supplyArea;

    @Column(name = "contract_area", precision = 10, scale = 2)
    private BigDecimal contractArea;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "floor_type", nullable = false, length = 20)
    private FloorType floorType = FloorType.GROUND;

    @Column(name = "floor")
    private Integer floor;

    // ★★ 값이 있으면 화면에 '중층'으로 찍고 **응답에서 floor 를 뺀다**(hiddenFloor 참고).
    @Enumerated(EnumType.STRING)
    @Column(name = "floor_band", length = 10)
    private FloorBand floorBand;

    @Column(name = "building_floor")
    private Integer buildingFloor;

    @Column(name = "basement_floor")
    private Integer basementFloor;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 4)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_base", length = 20)
    private DirectionBase directionBase;

    @Enumerated(EnumType.STRING)
    @Column(name = "heating", length = 20)
    private HeatingType heating;

    @Enumerated(EnumType.STRING)
    @Column(name = "entrance_type", length = 20)
    private EntranceType entranceType;

    @Builder.Default
    @Column(name = "can_parking", nullable = false)
    private boolean canParking = false;

    @Column(name = "parking_num")
    private Integer parkingNum;

    @Column(name = "has_elevator")
    private Boolean hasElevator;

    @Column(name = "household_num")
    private Integer householdNum;

    @Column(name = "building_approval_date")
    private LocalDate buildingApprovalDate;

    @Builder.Default
    @Column(name = "move_in_immediate", nullable = false)
    private boolean moveInImmediate = false;

    @Column(name = "move_in_date")
    private LocalDate moveInDate;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "resident_registration", nullable = false, length = 20)
    private AvailabilityAnswer residentRegistration = AvailabilityAnswer.ASK;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_insurance", nullable = false, length = 20)
    private AvailabilityAnswer depositInsurance = AvailabilityAnswer.ASK;

    @Builder.Default
    @Column(name = "is_short_lease", nullable = false)
    private boolean shortLease = false;

    @Column(name = "real_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal realLat;

    @Column(name = "real_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal realLng;

    @Column(name = "display_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal displayLat;

    @Column(name = "display_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal displayLng;

    @Column(name = "region_id")
    private Long regionId;

    @Column(name = "dong_name", length = 50)
    private String dongName;

    @Column(name = "address_jibun", nullable = false, length = 255)
    private String addressJibun;

    @Column(name = "address_road", length = 255)
    private String addressRoad;

    @Builder.Default
    @Column(name = "show_detail_address", nullable = false)
    private boolean showDetailAddress = false;

    @Column(name = "contact_sms_number", length = 30)
    private String contactSmsNumber;

    @Column(name = "contact_call_number", length = 30)
    private String contactCallNumber;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Builder.Default
    @Column(name = "view_count", nullable = false, updatable = false)
    private Long viewCount = 0L;

    @Builder.Default
    @Column(name = "inquiry_count", nullable = false, updatable = false)
    private Long inquiryCount = 0L;

    @Builder.Default
    @Column(name = "favorite_count", nullable = false, updatable = false)
    private Long favoriteCount = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public void publish(LocalDateTime now, LocalDateTime expiresAt) {
        if (!(status == ListingStatus.DRAFT || status == ListingStatus.HIDDEN)) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_PUBLISHABLE);
        }
        this.status = ListingStatus.ACTIVE;
        this.postedAt = postedAt == null ? now : postedAt;
        this.expiresAt = expiresAt;
    }

    public void unpublish() {
        if (!status.isVisibleToPublic()) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_ACTIVE);
        }
        this.status = ListingStatus.HIDDEN;
    }

    public void hideForRegionClosure() {
        if (!(status.isVisibleToPublic() || status == ListingStatus.EXPIRED)) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_ACTIVE);
        }
        this.status = ListingStatus.HIDDEN;
    }

    public void complete() {
        if (!status.isVisibleToPublic()) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_ACTIVE);
        }
        this.status = ListingStatus.COMPLETED;
    }

    public void reserve() {
        if (status != ListingStatus.ACTIVE) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_ACTIVE);
        }
        this.status = ListingStatus.RESERVED;
    }

    public void unreserve() {
        if (status != ListingStatus.RESERVED) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_RESERVED);
        }
        this.status = ListingStatus.ACTIVE;
    }

    public void renew(LocalDateTime newExpiresAt, boolean withinWindow) {
        if (status != ListingStatus.EXPIRED || !withinWindow) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_RENEWABLE);
        }
        this.status = ListingStatus.ACTIVE;
        this.expiresAt = newExpiresAt;
    }

    public void expire() {
        this.status = ListingStatus.EXPIRED;
    }

    public void archive() {
        this.status = ListingStatus.ARCHIVED;
    }

    public void block() {
        if (!(status == ListingStatus.DRAFT || status.isVisibleToPublic()
                || status == ListingStatus.HIDDEN || status == ListingStatus.EXPIRED)) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_BLOCKABLE);
        }

        this.status = ListingStatus.BLOCKED;
    }

    public void unblock() {
        if (status != ListingStatus.BLOCKED) {
            throw new ListingException(ListingErrorCode.LISTING_NOT_BLOCKED);
        }
        this.status = ListingStatus.HIDDEN;
    }

    public void changeDescription(String newDescription) {
        if (Objects.equals(this.description, newDescription)) {
            return;
        }
        this.description = newDescription;
        this.descriptionVersion += 1;
    }

    public void applyEditable(BrokerListingSaveRequest r,
                              BigDecimal[] displayCoords, Long resolvedRegionId) {
        this.category = r.category();
        this.roomType = r.roomType();
        this.division = r.divisionOrFalse();
        this.duplex = r.duplexOrFalse();
        this.title = r.title();
        this.sellingType = r.sellingType();
        this.deposit = r.deposit();
        this.monthlyRent = r.monthlyRent() == null ? 0L : r.monthlyRent();
        this.exclusiveArea = r.exclusiveArea();
        this.supplyArea = r.supplyArea();
        this.contractArea = r.contractArea();
        this.floorType = r.floorType() == null ? FloorType.GROUND : r.floorType();
        this.floor = r.floor();
        this.floorBand = r.floorBand();
        this.buildingFloor = r.buildingFloor();
        this.basementFloor = r.basementFloor();
        this.direction = r.direction();
        this.directionBase = r.directionBase();
        this.heating = r.heating();
        this.entranceType = r.entranceType();
        this.canParking = r.canParkingOrFalse();
        this.parkingNum = r.parkingNum();
        this.hasElevator = r.hasElevator();
        this.householdNum = r.householdNum();
        this.buildingApprovalDate = r.buildingApprovalDate();
        this.moveInImmediate = r.moveInImmediateOrFalse();
        this.moveInDate = r.moveInDate();
        this.residentRegistration = r.residentRegistration() == null
                ? AvailabilityAnswer.ASK : r.residentRegistration();
        this.depositInsurance = r.depositInsurance() == null
                ? AvailabilityAnswer.ASK : r.depositInsurance();
        this.shortLease = r.shortLeaseOrFalse();
        this.regionId = resolvedRegionId;
        this.dongName = r.dongName();
        this.addressJibun = r.addressJibun();
        this.addressRoad = r.addressRoad();
        this.showDetailAddress = r.showDetailAddressOrFalse();
        this.contactSmsNumber = r.contactSmsNumber();
        this.contactCallNumber = r.contactCallNumber();

        if (displayCoords != null) {
            this.realLat = r.lat();
            this.realLng = r.lng();
            this.displayLat = displayCoords[0];
            this.displayLng = displayCoords[1];
        }
    }

    public boolean hidesFloor() {
        return floorBand != null;
    }

    public Integer exposedFloor() {
        return hidesFloor() ? null : floor;
    }
}
