package com.kovil.domain.partner.enums;

import com.kovil.domain.user.enums.Permission;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@RequiredArgsConstructor
public enum PartnerDivisionType {

    REAL_ESTATE_BROKER(EnumSet.of(Permission.LISTING_MANAGE, Permission.RENTAL_REQUEST_HANDLE));

    private final Set<Permission> permissions;

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }
}
