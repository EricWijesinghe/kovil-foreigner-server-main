package com.kovil.domain.listing.exception;

import com.kovil.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ListingErrorCode implements ErrorCode {

    LISTING_NOT_FOUND("Listing not found.", HttpStatus.NOT_FOUND),

    LISTING_ACCESS_DENIED("This listing belongs to another office.", HttpStatus.FORBIDDEN),

    BROKER_PROFILE_NOT_FOUND("Your broker profile is not set up yet.", HttpStatus.FORBIDDEN),
    OFFICE_NOT_ACTIVE("Your office is not approved for advertising.", HttpStatus.FORBIDDEN),


    LISTING_NOT_PUBLISHABLE("Only a draft or a withdrawn listing can be published.", HttpStatus.CONFLICT),
    LISTING_NOT_ACTIVE("This listing is not currently published.", HttpStatus.CONFLICT),
    LISTING_NOT_RESERVED("This listing is not marked as reserved.", HttpStatus.CONFLICT),
    LISTING_NOT_RENEWABLE("The renewal window for this listing has closed.", HttpStatus.CONFLICT),

    LISTING_NOT_EDITABLE("This listing can no longer be changed.", HttpStatus.CONFLICT),

    LISTING_NOT_DELETABLE("A listing that has been published cannot be deleted.", HttpStatus.CONFLICT),

    PUBLISH_PHOTO_REQUIRED("Attach at least one photo before publishing.", HttpStatus.BAD_REQUEST),
    PUBLISH_MAINTENANCE_REQUIRED("Maintenance fee information is required before publishing.", HttpStatus.BAD_REQUEST),
    PUBLISH_DESCRIPTION_REQUIRED("A description is required before publishing.", HttpStatus.BAD_REQUEST),
    PUBLISH_RESIDENT_REGISTRATION_REQUIRED("Confirm whether resident registration is possible before publishing.", HttpStatus.BAD_REQUEST),

    OFFICE_REG_NO_DUPLICATED("That brokerage registration number is already in use by an approved office.", HttpStatus.CONFLICT),

    LISTING_IMAGE_LIMIT_EXCEEDED("You can attach up to 10 photos.", HttpStatus.BAD_REQUEST),
    AREA_RANGE_INVALID("Exclusive area must not exceed supply or contract area.", HttpStatus.BAD_REQUEST),
    FLOOR_INPUT_INVALID("Enter either an exact floor or a floor band, not both.", HttpStatus.BAD_REQUEST),

    LANGUAGE_NOT_SUPPORTED("That language is not supported.", HttpStatus.BAD_REQUEST),

    LISTING_IMAGE_INVALID("One of the photos cannot be attached.", HttpStatus.BAD_REQUEST),

    LISTING_REGION_INVALID("That region is not available.", HttpStatus.BAD_REQUEST),
    LISTING_RADIUS_INVALID("Nearby search needs a centre and a radius between 100 and 3,000 m.", HttpStatus.BAD_REQUEST),
    LISTING_POLYGON_INVALID("The drawn area is not valid. Draw it again.", HttpStatus.BAD_REQUEST),
    LISTING_POLYGON_TOO_LARGE("The drawn area is too large. Draw a smaller area.", HttpStatus.BAD_REQUEST),
    SAVED_AREA_NOT_FOUND("Saved area not found.", HttpStatus.NOT_FOUND),
    SAVED_AREA_LIMIT_EXCEEDED("You can save up to 20 areas. Delete one first.", HttpStatus.CONFLICT),

    LISTING_REPORT_REASON_REQUIRED("Select a reason for the report.", HttpStatus.BAD_REQUEST),
    LISTING_REPORT_DUPLICATE("You have already reported this listing.", HttpStatus.CONFLICT),
    LISTING_REPORT_NOT_FOUND("Report not found.", HttpStatus.NOT_FOUND),
    LISTING_REPORT_ALREADY_HANDLED("This report has already been handled.", HttpStatus.CONFLICT),

    LISTING_NOT_BLOCKED("This listing is not blocked.", HttpStatus.CONFLICT),
    LISTING_NOT_BLOCKABLE("This listing is already blocked or has ended.", HttpStatus.CONFLICT),

    PUBLISH_REGION_REQUIRED("Choose a district within the service area before publishing.", HttpStatus.BAD_REQUEST);

    private final String defaultMessage;
    private final HttpStatus httpStatus;

    @Override
    public String getCode() {
        return name();
    }
}