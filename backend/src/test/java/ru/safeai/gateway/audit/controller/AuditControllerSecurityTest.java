package ru.safeai.gateway.audit.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication
        .UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration
        .EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders
        .HttpSecurity;
import org.springframework.security.config.annotation.web.configuration
        .EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers
        .AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority
        .SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication
        .HttpStatusEntryPoint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito
        .MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.dto.AuditEventCursorResponse;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.service.AuditEventCursorService;
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuditController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        AuditControllerSecurityTest
                .TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class AuditControllerSecurityTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
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
            Instant.parse("2026-06-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditEventQueryService queryService;

    @MockitoBean
    private AuditEventCursorService cursorService;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(
                HttpSecurity http
        ) {
            return http
                    .csrf(
                            AbstractHttpConfigurer
                                    ::disable
                    )
                    .exceptionHandling(exceptions ->
                            exceptions
                                    .authenticationEntryPoint(
                                            new HttpStatusEntryPoint(
                                                    HttpStatus
                                                            .UNAUTHORIZED
                                            )
                                    )
                    )
                    .authorizeHttpRequests(authorize ->
                            authorize
                                    .anyRequest()
                                    .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void allEndpointsReturn401WhenAnonymous()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/audit-events")
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/cursor"
                        )
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(
                queryService,
                cursorService
        );
    }

    @Test
    void allEndpointsReturn403ForOrdinaryUser()
            throws Exception {
        Authentication userAuthentication =
                authToken(
                        userPrincipal()
                );

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                userAuthentication
                                        )
                                )
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/cursor"
                        )
                                .with(
                                        authentication(
                                                userAuthentication
                                        )
                                )
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                                .with(
                                        authentication(
                                                userAuthentication
                                        )
                                )
                )
                .andExpect(status().isForbidden());

        verifyNoInteractions(
                queryService,
                cursorService
        );
    }

    @Test
    void pageEndpointPassesPrincipalFilterAndDefaultPageable()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(queryService.findAll(
                any(),
                any(),
                any()
        )).thenReturn(Page.empty());

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk());

        var principalCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(
                                SafeAiUserPrincipal.class
                        );

        var filterCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(
                                AuditEventFilter.class
                        );

        var pageableCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(
                                Pageable.class
                        );

        verify(queryService).findAll(
                principalCaptor.capture(),
                filterCaptor.capture(),
                pageableCaptor.capture()
        );

        assertThat(principalCaptor.getValue())
                .isSameAs(principal);

        AuditEventFilter filter =
                filterCaptor.getValue();

        assertThat(filter.eventType()).isNull();
        assertThat(filter.userEmail()).isNull();
        assertThat(filter.userId()).isNull();
        assertThat(filter.organizationId()).isNull();

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize())
                .isEqualTo(50);

        Sort.Order order = Objects.requireNonNull(
                pageable.getSort()
                        .getOrderFor("createdAt")
        );

        assertThat(order.getDirection())
                .isEqualTo(Sort.Direction.DESC);

        verifyNoMoreInteractions(queryService);
        verifyNoInteractions(cursorService);
    }

    @Test
    void pageEndpointBindsAllFiltersForSuperAdmin()
            throws Exception {
        SafeAiUserPrincipal principal =
                superAdminPrincipal();

        when(queryService.findAll(
                any(),
                any(),
                any()
        )).thenReturn(Page.empty());

        Instant from =
                Instant.parse(
                        "2026-06-01T00:00:00Z"
                );

        Instant to =
                Instant.parse(
                        "2026-07-01T00:00:00Z"
                );

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .param(
                                        "eventType",
                                        AuditEventType
                                                .USER_LOGIN_SUCCESS
                                                .name()
                                )
                                .param(
                                        "userEmail",
                                        " ADMIN@Test.Com "
                                )
                                .param(
                                        "userId",
                                        USER_ID.toString()
                                )
                                .param(
                                        "dateFrom",
                                        from.toString()
                                )
                                .param(
                                        "dateTo",
                                        to.toString()
                                )
                                .param(
                                        "organizationId",
                                        ORGANIZATION_ID
                                                .toString()
                                )
                                .param("page", "2")
                                .param("size", "25")
                                .param(
                                        "sort",
                                        "createdAt,asc"
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk());

        var filterCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(
                                AuditEventFilter.class
                        );

        var pageableCaptor =
                org.mockito.ArgumentCaptor
                        .forClass(
                                Pageable.class
                        );

        verify(queryService).findAll(
                eq(principal),
                filterCaptor.capture(),
                pageableCaptor.capture()
        );

        AuditEventFilter filter =
                filterCaptor.getValue();

        assertThat(filter.eventType())
                .isEqualTo(
                        AuditEventType
                                .USER_LOGIN_SUCCESS
                );
        assertThat(filter.userEmail())
                .isEqualTo("admin@test.com");
        assertThat(filter.userId())
                .isEqualTo(USER_ID);
        assertThat(filter.dateFrom())
                .isEqualTo(from);
        assertThat(filter.dateTo())
                .isEqualTo(to);
        assertThat(filter.organizationId())
                .isEqualTo(ORGANIZATION_ID);

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isEqualTo(2);
        assertThat(pageable.getPageSize())
                .isEqualTo(25);
    }

    @Test
    void cursorEndpointPassesOpaqueCursorAndLimit()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(cursorService.findAll(
                any(),
                any(),
                any(),
                any()
        )).thenReturn(
                new AuditEventCursorResponse(
                        List.of(),
                        null,
                        false
                )
        );

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/cursor"
                        )
                                .param(
                                        "cursor",
                                        "opaque-cursor"
                                )
                                .param("limit", "25")
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items").isArray()
                )
                .andExpect(
                        jsonPath("$.hasNext")
                                .value(false)
                );

        verify(cursorService).findAll(
                eq(principal),
                any(AuditEventFilter.class),
                eq("opaque-cursor"),
                eq(25)
        );

        verifyNoInteractions(queryService);
    }

    @Test
    void userEndpointPassesPathVariable()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        when(queryService.findByUserId(
                any(),
                any(),
                any()
        )).thenReturn(Page.empty());

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isOk());

        verify(queryService).findByUserId(
                eq(USER_ID),
                eq(principal),
                any(Pageable.class)
        );
    }

    @Test
    void invalidUserUuidReturns400BeforeService()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/not-a-uuid"
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        adminPrincipal()
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("BAD_REQUEST")
                );

        verifyNoInteractions(queryService);
    }

    @Test
    void forbiddenServiceFailureMapsTo403()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        doThrow(
                new ForbiddenOperationException(
                        "Нельзя фильтровать аудит "
                                + "другой организации"
                )
        ).when(queryService).findAll(
                eq(principal),
                any(AuditEventFilter.class),
                any(Pageable.class)
        );

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .param(
                                        "organizationId",
                                        PLATFORM_ORGANIZATION_ID
                                                .toString()
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.status").value(403)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("FORBIDDEN")
                );
    }

    @Test
    void invalidSortFailureMapsTo400()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        doThrow(
                new BadRequestException(
                        "Сортировка по полю "
                                + "не разрешена: password"
                )
        ).when(queryService).findAll(
                eq(principal),
                any(AuditEventFilter.class),
                any(Pageable.class)
        );

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .param(
                                        "sort",
                                        "password,asc"
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        principal
                                                )
                                        )
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.status").value(400)
                )
                .andExpect(
                        jsonPath("$.error")
                                .value("BAD_REQUEST")
                )
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "Сортировка по полю "
                                                + "не разрешена: "
                                                + "password"
                                )
                );
    }

    private Authentication authToken(
            SafeAiUserPrincipal principal
    ) {
        return UsernamePasswordAuthenticationToken
                .authenticated(
                        principal,
                        null,
                        principal.getAuthorities()
                );
    }

    private SafeAiUserPrincipal userPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        "user@test.com",
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_ADMIN"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        "superadmin@test.com",
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}
