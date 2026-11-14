package com.kovil.domain.listing.enums;


public enum SellingType {
    MONTHLY_RENT,
    LEASE;

    public boolean hasMonthlyRent() {
        return this == MONTHLY_RENT;
    }
}
