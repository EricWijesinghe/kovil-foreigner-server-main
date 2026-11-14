package com.kovil.domain.listing.enums;

public enum FixedDetailType {

    WITH_DETAILS,
    BELOW_THRESHOLD,
    ABOVE_THRESHOLD_NO_DETAILS;

    public boolean hasItemBreakdown() {
        return this == WITH_DETAILS;
    }
}
