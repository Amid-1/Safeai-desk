package ru.safeai.gateway.usage.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UsageController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        UsageControllerSecurityTest.TestSecurityConfig.class,
        UsageControllerSecurityTest.TestClockConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class UsageControllerSecurityTest {

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000101"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final Instant NOW =
            Instant.parse("2026-07-13T00:00:00Z");

    private static final Instant DATE_FROM =
            Instant.parse("2026-06-01T00:00:00Z");

    private static final Instant DATE_TO =
            Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsageQueryService usageQueryService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize ->
                            authorize.anyRequest().authenticated()
                    )
                    .build();
        }
    }

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/data-quality",
            "/api/admin/usage/by-user/cccccccc-cccc-cccc-cccc-cccccccccccc",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void protectedEndpointsReturn4xxForAnonymous(
            String url
    ) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().is4xxClientError());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/data-quality",
            "/api/admin/usage/by-user/cccccccc-cccc-cccc-cccc-cccccccccccc",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void protectedEndpointsReturn403ForUserRole(
            String url
    ) throws Exception {
        mockMvc.perform(
                        get(url)
                                .with(
                                        user("user@test.com")
                                                .roles("USER")
                                )
                )
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/data-quality",
            "/api/admin/usage/by-user/cccccccc-cccc-cccc-cccc-cccccccccccc",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void protectedEndpointsReturn200ForAdmin(
            String url
    ) throws Exception {
        stubServiceResponses();

        mockMvc.perform(
                        get(url)
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage/summary",
            "/api/admin/usage/users",
            "/api/admin/usage/models",
            "/api/admin/usage/daily",
            "/api/admin/usage/data-quality",
            "/api/admin/usage/by-user/cccccccc-cccc-cccc-cccc-cccccccccccc",
            "/api/admin/usage/by-organization/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void protectedEndpointsReturn200ForSuperAdmin(
            String url
    ) throws Exception {
        stubServiceResponses();

        mockMvc.perform(
                        get(url)
                                .with(
                                        authentication(
                                                authToken(
                                                        superAdminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    void summaryUsesDefaultPagination()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageSummary(
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                eq(principal)
        )).thenReturn(emptySummarySlice());

        mockMvc.perform(
                        get("/api/admin/usage/summary")
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false));

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(usageQueryService).getUsageSummary(
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                pageableCaptor.capture(),
                eq(principal)
        );

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isZero();

        assertThat(pageable.getPageSize())
                .isEqualTo(50);

        assertThat(pageable.getSort().isUnsorted())
                .isTrue();
    }

    @Test
    void summaryPassesDateModelAndPaginationToService()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageSummary(
                eq(DATE_FROM),
                eq(DATE_TO),
                eq("gpt-4.1"),
                any(Pageable.class),
                eq(principal)
        )).thenReturn(emptySummarySlice());

        mockMvc.perform(
                        get("/api/admin/usage/summary")
                                .param(
                                        "dateFrom",
                                        DATE_FROM.toString()
                                )
                                .param(
                                        "dateTo",
                                        DATE_TO.toString()
                                )
                                .param(
                                        "model",
                                        "  gpt-4.1  "
                                )
                                .param("page", "2")
                                .param("size", "25")
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(usageQueryService).getUsageSummary(
                eq(DATE_FROM),
                eq(DATE_TO),
                eq("gpt-4.1"),
                pageableCaptor.capture(),
                eq(principal)
        );

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isEqualTo(2);

        assertThat(pageable.getPageSize())
                .isEqualTo(25);

        assertThat(pageable.getSort().isUnsorted())
                .isTrue();
    }

    @Test
    void usersEndpointPassesPageableToService()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageByUsers(
                eq(DATE_FROM),
                eq(DATE_TO),
                any(Pageable.class),
                eq(principal)
        )).thenReturn(emptyUserSlice());

        mockMvc.perform(
                        get("/api/admin/usage/users")
                                .param(
                                        "dateFrom",
                                        DATE_FROM.toString()
                                )
                                .param(
                                        "dateTo",
                                        DATE_TO.toString()
                                )
                                .param("page", "1")
                                .param("size", "20")
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(usageQueryService).getUsageByUsers(
                eq(DATE_FROM),
                eq(DATE_TO),
                pageableCaptor.capture(),
                eq(principal)
        );

        assertThat(
                pageableCaptor.getValue().getPageNumber()
        ).isEqualTo(1);

        assertThat(
                pageableCaptor.getValue().getPageSize()
        ).isEqualTo(20);
    }

    @Test
    void userEndpointUsesCanonicalPath()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageByUserId(
                eq(USER_ID),
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                eq(principal)
        )).thenReturn(emptySummarySlice());

        mockMvc.perform(
                        get(
                                "/api/admin/usage/by-user/{userId}",
                                USER_ID
                        )
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk());

        verify(usageQueryService).getUsageByUserId(
                eq(USER_ID),
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                eq(principal)
        );
    }

    @Test
    void organizationEndpointUsesCanonicalPath()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageByOrganizationId(
                eq(ORGANIZATION_ID),
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                eq(principal)
        )).thenReturn(emptySummarySlice());

        mockMvc.perform(
                        get(
                                "/api/admin/usage/by-organization/{organizationId}",
                                ORGANIZATION_ID
                        )
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk());

        verify(usageQueryService)
                .getUsageByOrganizationId(
                        eq(ORGANIZATION_ID),
                        nullable(Instant.class),
                        nullable(Instant.class),
                        nullable(String.class),
                        any(Pageable.class),
                        eq(principal)
                );
    }

    @Test
    void dataQualityPassesDateRangeToService()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        mockMvc.perform(
                        get("/api/admin/usage/data-quality")
                                .param(
                                        "dateFrom",
                                        DATE_FROM.toString()
                                )
                                .param(
                                        "dateTo",
                                        DATE_TO.toString()
                                )
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk());

        verify(usageQueryService).getDataQuality(
                eq(DATE_FROM),
                eq(DATE_TO),
                eq(principal)
        );
    }

    @Test
    void modelsAndDailyRemainNonPagedLists()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(usageQueryService.getUsageByModels(
                nullable(Instant.class),
                nullable(Instant.class),
                eq(principal)
        )).thenReturn(List.of());

        when(usageQueryService.getUsageDaily(
                nullable(Instant.class),
                nullable(Instant.class),
                eq(principal)
        )).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/admin/usage/models")
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(
                        get("/api/admin/usage/daily")
                                .with(
                                        authentication(
                                                authToken(principal)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "size=201",
            "size=0",
            "page=-1"
    })
    void invalidPaginationReturns400(
            String query
    ) throws Exception {
        String[] pair =
                query.split("=", 2);

        mockMvc.perform(
                        get("/api/admin/usage/summary")
                                .param(pair[0], pair[1])
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        verify(
                usageQueryService,
                never()
        ).getUsageSummary(
                any(),
                any(),
                any(),
                any(Pageable.class),
                any()
        );
    }

    @Test
    void modelLongerThan100CharactersReturns400()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/usage/summary")
                                .param(
                                        "model",
                                        "a".repeat(101)
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        verify(
                usageQueryService,
                never()
        ).getUsageSummary(
                any(),
                any(),
                any(),
                any(Pageable.class),
                any()
        );
    }

    @Test
    void malformedDateReturns400()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/usage/summary")
                                .param(
                                        "dateFrom",
                                        "2026-07-12"
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/usage-summary",
            "/api/admin/usage/users/cccccccc-cccc-cccc-cccc-cccccccccccc",
            "/api/admin/usage/organizations/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    })
    void legacyRoutesReturn404(
            String url
    ) throws Exception {
        mockMvc.perform(
                        get(url)
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(status().isNotFound());
    }

    private void stubServiceResponses() {
        when(usageQueryService.getUsageSummary(
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(emptySummarySlice());

        when(usageQueryService.getUsageByUsers(
                nullable(Instant.class),
                nullable(Instant.class),
                any(Pageable.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(emptyUserSlice());

        when(usageQueryService.getUsageByModels(
                nullable(Instant.class),
                nullable(Instant.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(List.of());

        when(usageQueryService.getUsageDaily(
                nullable(Instant.class),
                nullable(Instant.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(List.of());

        when(usageQueryService.getUsageByUserId(
                any(UUID.class),
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(emptySummarySlice());

        when(usageQueryService.getUsageByOrganizationId(
                any(UUID.class),
                nullable(Instant.class),
                nullable(Instant.class),
                nullable(String.class),
                any(Pageable.class),
                any(SafeAiUserPrincipal.class)
        )).thenReturn(emptySummarySlice());
    }

    private Slice<UsageSummaryResponse> emptySummarySlice() {
        return new SliceImpl<>(
                List.of(),
                Pageable.ofSize(50),
                false
        );
    }

    private Slice<UsageUserSummaryResponse> emptyUserSlice() {
        return new SliceImpl<>(
                List.of(),
                Pageable.ofSize(50),
                false
        );
    }

    private Authentication authToken(
            SafeAiUserPrincipal principal
    ) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"
                        )
                )
        );
    }
}
