package ru.safeai.gateway.usage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
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
import ru.safeai.gateway.model.controller.OrganizationBudgetStatusController;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.dto.OrganizationBudgetStatusResponse;
import ru.safeai.gateway.model.service.OrganizationBudgetStatusService;
import ru.safeai.gateway.usage.dto.UsageCostSummary;
import ru.safeai.gateway.usage.dto.UsageOrganizationSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageResponseSummary;
import ru.safeai.gateway.usage.dto.UsageTokenSummary;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercise actual MVC mapping, binding, serialization and method security, not a mocked HTTP route. */
@WebMvcTest(controllers = {UsageController.class, OrganizationBudgetStatusController.class},
        properties = "spring.jackson.default-property-inclusion=non_null",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserStatusFilter.class))
@Import({UsageOrganizationControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class, ApiErrorResponseFactory.class})
@ActiveProfiles("test")
class UsageOrganizationControllerSecurityTest {
    private static final String PATH = "/api/admin/usage/organizations";
    private static final UUID ORG = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID USER = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-20T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UsageQueryService usageQueryService;

    @MockitoBean
    private OrganizationBudgetStatusService budgetService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(TO, ZoneOffset.UTC);
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults()).build();
        }
    }

    @Test
    void superAdminReceivesRealSliceContractAndAllReportParameters() throws Exception {
        var principal = principal("SUPER_ADMIN");
        var pageable = PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "tokens"));
        var row = new UsageOrganizationSummaryResponse(ORG, "Demo",
                new UsageResponseSummary(1, 1, 0, 0, 0),
                new UsageTokenSummary(9, 16, 0, 0, 1, 0, 0, 0),
                new UsageCostSummary(new BigDecimal("0.000000000001"), 1, 0, 0, 0, 0));
        when(usageQueryService.getUsageByOrganizations(FROM, TO, "mock-safeai", USER, pageable, principal))
                .thenReturn(new SliceImpl<>(List.of(row), pageable, false));
        mvc.perform(get(PATH).with(authentication(auth(principal)))
                        .param("dateFrom", FROM.toString()).param("dateTo", TO.toString())
                        .param("model", "mock-safeai").param("userId", USER.toString())
                        .param("page", "1").param("size", "1").param("order", "tokens"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.content[0].organizationName").value("Demo"))
                .andExpect(jsonPath("$.content[0].cost.knownCostUsd").value("0.000000000001"))
                .andExpect(jsonPath("$.content[0].usage.confirmedTotalTokens").value(25))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.totalElements").doesNotExist());
        verify(usageQueryService).getUsageByOrganizations(FROM, TO, "mock-safeai", USER, pageable, principal);
    }

    @Test
    void adminAndUserCannotReachGlobalReportService() throws Exception {
        for (String role : List.of("ADMIN", "USER")) {
            mvc.perform(get(PATH).with(authentication(auth(principal(role)))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(usageQueryService);
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(usageQueryService);
    }

    @Test
    void rejectsInvalidSortAndOversizedPageBeforeCallingService() throws Exception {
        var principal = principal("SUPER_ADMIN");
        mvc.perform(get(PATH).with(authentication(auth(principal))).param("order", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).with(authentication(auth(principal))).param("size", "201"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).with(authentication(auth(principal))).param("page", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(usageQueryService);
    }

    @Test
    void budgetContractUsesUtcStringsAndExplicitNullEvenWhenNoLimitIsConfigured() throws Exception {
        var principal = principal("SUPER_ADMIN");
        var end = Instant.parse("2026-12-01T00:00:00Z");
        when(budgetService.current(ORG, principal)).thenReturn(new OrganizationBudgetStatusResponse(
                ORG, 2, true, BudgetEnforcement.SOFT, FROM, end, TO,
                null, "0.000000000001", "2", "2.000000000001", 0, null, "NO_LIMIT"));
        mvc.perform(get("/api/admin/models/policies/{organizationId}/budget-status", ORG)
                        .with(authentication(auth(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.periodStart").value(FROM.toString()))
                .andExpect(jsonPath("$.periodEnd").value(end.toString()))
                .andExpect(jsonPath("$.asOf").value(TO.toString()))
                .andExpect(jsonPath("$.monthlyBudgetUsd").hasJsonPath())
                .andExpect(jsonPath("$.monthlyBudgetUsd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.remainingUsd").hasJsonPath())
                .andExpect(jsonPath("$.remainingUsd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.knownActualUsd").value("0.000000000001"))
                .andExpect(jsonPath("$.status").value("NO_LIMIT"));
        verify(budgetService).current(ORG, principal);
    }

    @Test
    void budgetRouteRejectsAnonymousUserAndMalformedTenant() throws Exception {
        String path = "/api/admin/models/policies/{organizationId}/budget-status";
        mvc.perform(get(path, ORG)).andExpect(status().isUnauthorized());
        mvc.perform(get(path, ORG).with(authentication(auth(principal("USER")))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path, "invalid").with(authentication(auth(principal("ADMIN")))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(budgetService);
    }

    @Test
    void budgetContractPreservesConfiguredAmountsAndUnknownRemainingUnderNonNullInclusion() throws Exception {
        var principal = principal("SUPER_ADMIN");
        var end = Instant.parse("2026-12-01T00:00:00Z");
        var asOf = Instant.parse("2026-11-19T18:47:57.109125Z");
        when(budgetService.current(ORG, principal)).thenReturn(new OrganizationBudgetStatusResponse(
                ORG, 2, true, BudgetEnforcement.SOFT, FROM, end, asOf,
                "10.000000000001", "0.000000000000", "0", "0.000000000000", 1, null, "UNKNOWN"));
        mvc.perform(get("/api/admin/models/policies/{organizationId}/budget-status", ORG)
                        .with(authentication(auth(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyBudgetUsd").value("10.000000000001"))
                .andExpect(jsonPath("$.remainingUsd").hasJsonPath())
                .andExpect(jsonPath("$.remainingUsd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.knownActualUsd").value("0.000000000000"))
                .andExpect(jsonPath("$.asOf").value(asOf.toString()))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        verify(budgetService).current(ORG, principal);
    }

    private static SafeAiUserPrincipal principal(String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(USER, ORG, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static UsernamePasswordAuthenticationToken auth(SafeAiUserPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
