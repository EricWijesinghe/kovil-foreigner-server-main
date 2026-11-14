package com.kovil.domain.rentalrequest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;


@ConfigurationProperties(prefix = "rental-request")
public record RentalRequestProperties(int maxActivePerUser, @DefaultValue("50") int maxClaimsPerOffice) {
}
