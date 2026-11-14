package com.kovil.domain.partner;

import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.crypto.Encryptor;
import com.kovil.infra.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 파트너 내 정보의 본인 휴대폰을 **진짜 암호화기 · 진짜 보안 필터 · 진짜 직렬화**로 태운다(H2).
 * ★ 저장 칸은 암호문이다 — 응답에는 복호화한 번호가 나가야 하고 암호문이 새면 안 된다.
 * ★ 휴대폰이 없는 계정도 화면이 열린다(칸은 null).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PartnerMePhoneApiTest {

    private static final String ME = "/api/v1/partner/me";

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired UserRepository userRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired Encryptor encryptor;

    private String bearerOf(String uuid, String cipherPhone) {
        User partner = userRepository.save(User.builder()
                .uuid(uuid).name("홍길동").phone(cipherPhone)
                .nickname(uuid).nicknameNormalized(uuid).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build());
        divisionRepository.save(PartnerDivision.builder().uuid("pd-" + uuid).userId(partner.getId())
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.PENDING).build());
        return "Bearer " + jwtTokenProvider.issueAccessToken(partner.getUuid(), UserRole.PARTNER, Set.of());
    }

    @Test
    @DisplayName("본인 휴대폰을 복호화해 숫자만으로 싣는다 — 암호문은 응답에 없다")
    void me_carries_decrypted_phone() throws Exception {
        String cipher = encryptor.encrypt("01000000000");

        String body = mockMvc.perform(get(ME).header("Authorization", bearerOf("pm-partner", cipher)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("01000000000"))
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(cipher);
    }

    @Test
    @DisplayName("휴대폰이 없는 계정도 열리고 칸은 null 이다(키는 있다)")
    void me_without_phone() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", bearerOf("pm-nophone", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(nullValue()));
    }
}
