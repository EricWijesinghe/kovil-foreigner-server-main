package com.kovil.domain.file.service;

import com.kovil.domain.file.entity.StoredFile;
import com.kovil.domain.file.enums.FilePurpose;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 인증만 되면 누구나 민감 파일 URL을 받을 수 있던 구멍이 닫혀 있는지 본다.
class FileAccessPolicyTest {

    private final FileAccessPolicy policy = new FileAccessPolicy();

    private static <T> T withId(T entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }

    // 정책은 이제 **DB에서 읽은 활성 유저**로 판정한다(토큰 클레임 아님) — 정지·강등 즉시 반영 축.
    private static User viewer(UserRole role, Long id) {
        return withId(User.builder().uuid("u").role(role).build(), id);
    }

    private static StoredFile file(FilePurpose purpose, Long uploaderId) {
        return StoredFile.builder()
                .storageKey("k").contentType("image/png").byteSize(1L)
                .purpose(purpose).uploaderId(uploaderId).build();
    }

    @Test
    @DisplayName("인증 문서는 본인만 볼 수 있다")
    void verification_doc_owner_only() {
        StoredFile passport = file(FilePurpose.VERIFICATION_DOC, 7L);

        assertThat(policy.canRead(passport, viewer(UserRole.USER, 7L))).isTrue();
        assertThat(policy.canRead(passport, viewer(UserRole.USER, 99L))).isFalse();
    }

    // 심사자가 못 보면 심사가 불가능하다.
    @Test
    @DisplayName("인증 심사 권한이 있으면 남의 인증 문서도 볼 수 있다")
    void reviewer_can_read() {
        StoredFile passport = file(FilePurpose.VERIFICATION_DOC, 7L);

        assertThat(policy.canRead(passport, viewer(UserRole.ADMIN, 99L))).isTrue();
    }

    // 파트너는 외부 사업자다 — 유학생의 신분 증빙에 닿을 근거가 없다.
    @Test
    @DisplayName("파트너는 남의 인증 문서를 볼 수 없다 — 심사 권한이 없다")
    void partner_cannot_read_verification_doc() {
        StoredFile passport = file(FilePurpose.VERIFICATION_DOC, 7L);

        assertThat(policy.canRead(passport, viewer(UserRole.PARTNER, 99L))).isFalse();
    }

    /*
     * 중개사무소 등록증 — 올린 대표자 본인과 부문 심사자(PARTNER_APPROVE)만 본다.
     * ★ 다른 파트너(다른 사무소 대표)에게 열리면 경쟁 사무소의 공문서가 새고,
     *   유학생에게 열리면 사무소 대표자의 서류가 아무에게나 나간다.
     */
    @Test
    @DisplayName("등록증은 올린 본인과 부문 심사 권한자만 볼 수 있다")
    void broker_license_owner_and_partner_reviewer_only() {
        StoredFile license = file(FilePurpose.BROKER_LICENSE, 7L);

        assertThat(policy.canRead(license, viewer(UserRole.PARTNER, 7L))).as("본인").isTrue();
        assertThat(policy.canRead(license, viewer(UserRole.ADMIN, 99L))).as("심사자").isTrue();
        assertThat(policy.canRead(license, viewer(UserRole.PARTNER, 99L))).as("다른 파트너").isFalse();
        assertThat(policy.canRead(license, viewer(UserRole.USER, 99L))).as("유학생").isFalse();
    }

    @Test
    @DisplayName("등록증은 민감 파일이다 — 공개 URL을 만들지 않는다")
    void broker_license_is_sensitive() {
        assertThat(FilePurpose.BROKER_LICENSE.isSensitive()).isTrue();
        assertThat(FilePurpose.BROKER_LICENSE.isRetired()).isFalse();
    }

    // 소유 도메인이 사라져 "누구의 파일인가"를 판정할 수 없다 — 비민감 용도(은행 아이콘)도,
    // 업로더 본인도, 관리자도 예외가 아니다.
    @Test
    @DisplayName("은퇴한 용도는 누구에게도 열리지 않는다")
    void retired_purposes_are_closed_to_everyone() {
        for (FilePurpose purpose : new FilePurpose[]{
                FilePurpose.CONTRACT_DOC, FilePurpose.MOVE_IN_PHOTO, FilePurpose.BANK_ICON}) {
            StoredFile retired = file(purpose, 7L);
            assertThat(policy.canRead(retired, viewer(UserRole.USER, 7L))).as("업로더 %s", purpose).isFalse();
            assertThat(policy.canRead(retired, viewer(UserRole.PARTNER, 99L))).as("파트너 %s", purpose).isFalse();
            assertThat(policy.canRead(retired, viewer(UserRole.ADMIN, 99L))).as("관리자 %s", purpose).isFalse();
        }
    }

    @Test
    @DisplayName("비민감 파일은 인증된 사용자면 열린다 — 어차피 공개 URL이 있다")
    void public_files_are_open() {
        for (FilePurpose purpose : new FilePurpose[]{
                FilePurpose.COMMUNITY_IMAGE, FilePurpose.PROFILE_IMAGE, FilePurpose.LISTING_IMAGE}) {
            assertThat(policy.canRead(file(purpose, 7L), viewer(UserRole.USER, 99L)))
                    .as("%s", purpose).isTrue();
        }
    }

    // 모든 민감 용도가 명시적으로 판정된다 — default가 조용히 열리면 INV-08이 깨진다.
    @Test
    @DisplayName("민감 용도는 전부 명시적으로 판정된다(기본 거부)")
    void every_sensitive_purpose_denies_by_default() {
        for (FilePurpose purpose : FilePurpose.values()) {
            if (!purpose.isSensitive()) {
                continue;
            }
            assertThat(policy.canRead(file(purpose, 7L), viewer(UserRole.USER, 999L)))
                    .as("%s가 남에게 열려 있다", purpose).isFalse();
        }
    }
}
