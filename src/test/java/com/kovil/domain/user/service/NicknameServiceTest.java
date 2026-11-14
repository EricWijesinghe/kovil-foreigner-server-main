package com.kovil.domain.user.service;

import com.kovil.domain.user.repository.UserRepository;
import com.kovil.domain.user.service.support.NicknameGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;

@ExtendWith(MockitoExtension.class)
class NicknameServiceTest {

    @Mock NicknameGenerator generator;
    @Mock UserRepository userRepository;

    @InjectMocks NicknameService service;

    private void normalizeIsLowercase() {
        given(generator.normalize(anyString())).willAnswer(inv ->
                ((String) inv.getArgument(0)).toLowerCase());
    }

    @Test
    @DisplayName("비어 있는 첫 후보를 그대로 쓴다")
    void first_free_candidate_wins() {
        given(generator.generate(4)).willReturn("CalmRiver0042");
        normalizeIsLowercase();
        given(userRepository.existsByNicknameNormalized("calmriver0042")).willReturn(false);

        NicknameService.Allocation allocation = service.allocate();

        assertThat(allocation.nickname()).isEqualTo("CalmRiver0042");
        assertThat(allocation.normalized()).isEqualTo("calmriver0042");
    }

    @Test
    @DisplayName("충돌하면 새 후보를 다시 뽑는다")
    void collision_retries_with_new_candidate() {
        given(generator.generate(4)).willReturn("Taken0001", "Taken0001", "Free0002");
        normalizeIsLowercase();
        given(userRepository.existsByNicknameNormalized("taken0001")).willReturn(true);
        given(userRepository.existsByNicknameNormalized("free0002")).willReturn(false);

        assertThat(service.allocate().nickname()).isEqualTo("Free0002");
        then(generator).should(atLeast(3)).generate(4);
    }

    // 4자리가 다 막히면 자리수를 늘려 변형 공간을 10배씩 넓힌다.
    @Test
    @DisplayName("4자리가 모두 막히면 5자리로 물러선다")
    void widens_digits_when_stage_exhausted() {
        given(generator.generate(4)).willReturn("Taken0001");
        given(generator.generate(5)).willReturn("Free00002");
        normalizeIsLowercase();
        given(userRepository.existsByNicknameNormalized("taken0001")).willReturn(true);
        given(userRepository.existsByNicknameNormalized("free00002")).willReturn(false);

        assertThat(service.allocate().nickname()).isEqualTo("Free00002");
    }

    // 사용자에게 "닉네임을 만들지 못했다"는 오류가 나가면 안 된다 —
    // 사다리를 소진해도 후보를 돌려주고 최종 판정은 유니크 제약에 맡긴다.
    @Test
    @DisplayName("사다리를 다 써도 예외 없이 후보를 돌려준다")
    void never_fails_signup() {
        given(generator.generate(anyInt())).willReturn("AlwaysTaken0000");
        normalizeIsLowercase();
        given(userRepository.existsByNicknameNormalized("alwaystaken0000")).willReturn(true);

        NicknameService.Allocation allocation = service.allocate();

        assertThat(allocation.nickname()).isEqualTo("AlwaysTaken0000");
        assertThat(allocation.normalized()).isEqualTo("alwaystaken0000");
    }
}
