package com.kovil.domain.user.repository;

import com.kovil.domain.user.entity.UserAuth;
import com.kovil.domain.user.enums.AuthType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface UserAuthRepository extends JpaRepository<UserAuth, Long> {

    boolean existsByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType authType, String identifier);

    Optional<UserAuth> findByAuthTypeAndIdentifierAndDeletedAtIsNull(AuthType authType, String identifier);

    Optional<UserAuth> findByUserIdAndAuthTypeAndDeletedAtIsNull(Long userId, AuthType authType);

    // 회원 상세의 "가입 경로". 한 계정에 여러 수단이 붙을 수 있다(이메일 + 소셜 연동).
    List<UserAuth> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 탈퇴 시 해제할 대상 — **soft delete 된 행까지 포함해 전부** 가져온다.
     *
     * 이미 해제된 수단도 uq_user_auth_type_identifier 슬롯을 계속 붙잡고 있어서,
     * 남겨 두면 같은 신원으로 재가입할 때 유니크 위반이 난다.
     */
    List<UserAuth> findAllByUserId(Long userId);

    // Apple 알림 전용. 엔티티를 고쳐 커밋하면 전 칼럼 UPDATE 가 나가는데, 그 사이 탈퇴가 커밋되면
    // 낡은 스냅샷의 deleted_at=NULL 이 덮여 탈퇴 계정의 Apple 연결이 되살아난다. 살아 있는 행에만 거는 조건부 갱신이다.
    @Modifying
    @Query("""
            update UserAuth a
            set a.providerRefreshToken = null, a.providerClientId = null
            where a.id = :id and a.deletedAt is null
            """)
    int clearProviderRefreshTokenIfLinked(@Param("id") Long id);
}
