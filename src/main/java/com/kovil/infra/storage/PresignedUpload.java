package com.kovil.infra.storage;

import java.time.LocalDateTime;

// 공개 URL은 confirm(확정 이동) 이후에야 의미가 있으므로 여기 없다.
public record PresignedUpload(String key, String uploadUrl, LocalDateTime expiresAt) {
}
