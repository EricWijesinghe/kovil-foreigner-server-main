package com.kovil.common.config;

import com.kovil.domain.language.entity.Language;
import com.kovil.domain.language.repository.LanguageRepository;
import com.kovil.domain.listing.entity.BrokerProfile;
import com.kovil.domain.listing.entity.BrokerageOffice;
import com.kovil.domain.listing.entity.Listing;
import com.kovil.domain.listing.enums.*;
import com.kovil.domain.listing.repository.BrokerProfileRepository;
import com.kovil.domain.listing.repository.BrokerageOfficeRepository;
import com.kovil.domain.listing.repository.ListingDescriptionTranslationRepository;
import com.kovil.domain.listing.repository.ListingRepository;
import com.kovil.domain.partner.entity.PartnerDivision;
import com.kovil.domain.partner.enums.PartnerDivisionStatus;
import com.kovil.domain.partner.enums.PartnerDivisionType;
import com.kovil.domain.partner.repository.PartnerDivisionRepository;
import com.kovil.domain.user.entity.User;
import com.kovil.domain.user.enums.UserRole;
import com.kovil.domain.user.enums.UserStatus;
import com.kovil.domain.user.repository.UserRepository;
import com.kovil.infra.jwt.JwtTokenProvider;
import com.kovil.infra.nearby.NearbyPlaceClient;
import com.kovil.infra.nearby.NearbySearchResult;
import com.kovil.infra.translation.TranslationClient;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * OSIV 는 외부 벤더를 부르는 경로(주변시설 · 번역)에서만 빠지고, 나머지 경로에는 그대로 걸린다.
 * ★ OSIV 아래에서는 요청 안 첫 조회가 잡은 DB 커넥션을 요청이 끝날 때까지 쥔다 — 벤더가 느리면 그동안 풀이 마른다.
 *   그래서 벤더 목이 불리는 바로 그 순간에 요청에 묶인 EntityManager 가 없는지 본다.
 * ★ @Transactional 을 붙이지 않는다 — 테스트 트랜잭션이 커넥션을 쥐면 볼 것이 없다. 만든 행은 끝에서 지운다
 *   (테스트 H2 는 컨텍스트끼리 공유하는 이름 붙은 메모리 DB 다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenEntityManagerInViewTest {

    private static final String LANGUAGE = "zz";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;
    @Autowired UserRepository userRepository;
    @Autowired BrokerageOfficeRepository officeRepository;
    @Autowired BrokerProfileRepository profileRepository;
    @Autowired PartnerDivisionRepository divisionRepository;
    @Autowired ListingRepository listingRepository;
    @Autowired ListingDescriptionTranslationRepository translationRepository;
    @Autowired LanguageRepository languageRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    @MockitoBean NearbyPlaceClient nearbyPlaceClient;
    @MockitoBean TranslationClient translationClient;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<Boolean> boundDuringVendorCall = new AtomicReference<>();
    private Long userId;
    private Long studentId;
    private String studentUuid;
    private Long officeId;
    private Long divisionId;
    private Long profileId;
    private Long listingId;
    private Long languageId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(User.builder()
                .uuid("u-osiv-" + tag).name("홍길동")
                .nickname("osiv-" + tag).nicknameNormalized("osiv-" + tag).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.PARTNER).build()).getId();
        officeId = officeRepository.save(BrokerageOffice.builder().uuid("o-osiv-" + tag).regNo("99999-2026-" + tag.substring(0, 5))
                .name("테스트공인중개사사무소").ceoName("홍길동")
                .address("서울특별시 서대문구").tel("02-000-0000").build()).getId();
        profileId = profileRepository.save(BrokerProfile.builder().userId(userId).officeId(officeId).build()).getId();
        divisionId = divisionRepository.save(PartnerDivision.builder().uuid("pd-osiv-" + tag).userId(userId)
                .division(PartnerDivisionType.REAL_ESTATE_BROKER).status(PartnerDivisionStatus.APPROVED)
                .submittedAt(LocalDateTime.of(2026, 10, 1, 0, 0)).build()).getId();
        // 주변시설은 (매물 id, 흐린 좌표)로 Redis 에 캐시한다 — 지난 실행의 캐시에 걸리지 않게 좌표를 실행마다 바꾼다.
        BigDecimal lat = new BigDecimal("33.0000000")
                .add(BigDecimal.valueOf(Math.floorMod(tag.hashCode(), 1_000_000) / 10_000_000.0))
                .setScale(7, RoundingMode.HALF_UP);
        listingId = listingRepository.saveAndFlush(Listing.builder()
                .uuid("osiv-" + tag).listingNo("LS-261013-" + tag.toUpperCase())
                .officeId(officeId).createdByUserId(userId)
                .category(ListingCategory.ONE_TWO_ROOM).roomType(RoomType.ONE_ROOM)
                .title("테스트").description("테스트 설명").sellingType(SellingType.MONTHLY_RENT)
                .deposit(3_000_000L).monthlyRent(300_000L)
                .exclusiveArea(new BigDecimal("26.44")).floorType(FloorType.GROUND)
                .residentRegistration(AvailabilityAnswer.ASK).depositInsurance(AvailabilityAnswer.ASK)
                .realLat(lat).realLng(new BigDecimal("126.5000000")).displayLat(lat).displayLng(new BigDecimal("126.5000000"))
                .addressJibun("서울특별시 서대문구 창천동")
                .postedAt(LocalDateTime.of(2026, 10, 1, 0, 0))
                .status(ListingStatus.ACTIVE).build()).getId();
        studentUuid = "u-osiv-st-" + tag;
        studentId = userRepository.save(User.builder()
                .uuid(studentUuid).name("홍길동")
                .nickname("osiv-st-" + tag).nicknameNormalized("osiv-st-" + tag).isNicknameCustom(false)
                .preferredLanguage("ko").status(UserStatus.ACTIVE).role(UserRole.USER).build()).getId();
        languageId = languageRepository.findByCodeAndActiveTrue(LANGUAGE).isPresent() ? null
                : languageRepository.save(Language.builder().code(LANGUAGE).nativeName("Test").englishName("Test")
                        .active(true).sortOrder(99).build()).getId();
    }

    @AfterEach
    void tearDown() {
        translationRepository.findAll().stream()
                .filter(row -> listingId.equals(row.getListingId()))
                .forEach(translationRepository::delete);
        listingRepository.deleteById(listingId);
        divisionRepository.deleteById(divisionId);
        profileRepository.deleteById(profileId);
        officeRepository.deleteById(officeId);
        userRepository.deleteById(userId);
        userRepository.deleteById(studentId);
        if (languageId != null) {
            languageRepository.deleteById(languageId);
        }
    }

    // 요청에 묶인 EntityManager 가 없으면 조회가 쓴 EntityManager 는 이미 닫혀 커넥션이 풀로 돌아가 있다.
    // (풀의 사용 중 개수는 스케줄러가 같은 풀을 쓰므로 보지 않는다 — 흔들린다.)
    private void recordState() {
        boundDuringVendorCall.set(TransactionSynchronizationManager.hasResource(entityManagerFactory));
    }

    @Test
    @DisplayName("주변시설 — 카카오를 부르는 동안 요청에 묶인 EntityManager(= 쥐고 있는 DB 커넥션)가 없다")
    void nearby_releases_the_connection_before_the_vendor() throws Exception {
        given(nearbyPlaceClient.search(anyString(), any(), any(), anyInt(), anyInt())).willAnswer(invocation -> {
            recordState();
            return new NearbySearchResult(0, List.of());
        });

        mockMvc.perform(get("/api/v1/listings/{uuid}/nearby", "osiv-" + tag)).andExpect(status().isOk());

        assertThat(boundDuringVendorCall.get()).isFalse();
    }

    @Test
    @DisplayName("번역 — 번역 벤더를 부르는 동안 요청에 묶인 EntityManager(= 쥐고 있는 DB 커넥션)가 없다")
    void translate_releases_the_connection_before_the_vendor() throws Exception {
        given(translationClient.engineName()).willReturn("test-engine");
        given(translationClient.translate(anyString(), anyString())).willAnswer(invocation -> {
            recordState();
            return "translated";
        });

        mockMvc.perform(post("/api/v1/listings/{uuid}/description/translate", "osiv-" + tag).param("language", LANGUAGE)
                        .header("Authorization", "Bearer " + jwtTokenProvider.issueAccessToken(studentUuid, UserRole.USER)))
                .andExpect(status().isOk());

        assertThat(boundDuringVendorCall.get()).isFalse();
    }

    // 나머지 경로는 그대로 OSIV — 기본값을 끄면서 전역으로 빠지면 지연 로딩에 기대던 곳이 깨진다.
    @Test
    @DisplayName("OSIV 는 벤더 경로 두 곳에서만 빠지고 다른 API 에는 걸린다")
    void osiv_still_wraps_other_paths() throws Exception {
        assertThat(hasOsiv("GET", "/api/v1/listings/osiv-" + tag)).isTrue();
        assertThat(hasOsiv("GET", "/api/v1/listings")).isTrue();
        assertThat(hasOsiv("GET", "/api/v1/search/places")).isTrue();
        assertThat(hasOsiv("GET", "/api/v1/listings/osiv-" + tag + "/nearby")).isFalse();
        assertThat(hasOsiv("POST", "/api/v1/listings/osiv-" + tag + "/description/translate")).isFalse();
    }

    private boolean hasOsiv(String method, String path) throws Exception {
        HandlerExecutionChain chain = handlerMapping.getHandler(new MockHttpServletRequest(method, path));
        assertThat(chain).as(path).isNotNull();
        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            if (interceptor instanceof WebRequestHandlerInterceptorAdapter adapter
                    && ReflectionTestUtils.getField(adapter, "requestInterceptor") instanceof OpenEntityManagerInViewInterceptor) {
                return true;
            }
        }
        return false;
    }
}
