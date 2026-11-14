package com.kovil.domain.rentalrequest.repository;

import java.time.LocalDateTime;

public record RentalRequestLastRelease(Long requestId, LocalDateTime releasedAt) {
}