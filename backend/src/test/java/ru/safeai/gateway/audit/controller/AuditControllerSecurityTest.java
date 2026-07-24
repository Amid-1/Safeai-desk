package ru.safeai.gateway.audit.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
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
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.service.AuditEventQueryService;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
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
import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.user;
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
        AuditControllerSecurityTest.TestSecurityConfig.class,
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
    private AuditEventQueryService auditEventQueryService;

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
                    .csrf(AbstractHttpConfigurer::disable)
                    .exceptionHandling(exceptions ->
                            exceptions.authenticationEntryPoint(
                                    new HttpStatusEntryPoint(
                                            HttpStatus.UNAUTHORIZED
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
    void findAllReturns401WhenAnonymous()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/audit-events")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditEventQueryService);
    }

    @Test
    void findByUserIdReturns401WhenAnonymous()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditEventQueryService);
    }

    @Test
    void findAllReturns403WhenUserRole()
            throws Exception {
        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .with(
                                        user("user@test.com")
                                                .roles("USER")
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403))
                .andExpect(jsonPath("$.error")
                        .value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Доступ запрещён"))
                .andExpect(jsonPath("$.path")
                        .value("/api/admin/audit-events"));

        verifyNoInteractions(auditEventQueryService);
    }

    @Test
    void findByUserIdReturns403WhenUserRole()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                                .with(
                                        user("user@test.com")
                                                .roles("USER")
                                )
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403))
                .andExpect(jsonPath("$.error")
                        .value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("Доступ запрещён"))
                .andExpect(jsonPath("$.path")
                        .value(
                                "/api/admin/audit-events/users/"
                                        + USER_ID
                        ));

        verifyNoInteractions(auditEventQueryService);
    }

    @Test
    void findAllReturns200ForAdminAndPassesDefaultPageable()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isOk());

        var principalCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        SafeAiUserPrincipal.class
                );

        var filterCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        AuditEventFilter.class
                );

        var pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(auditEventQueryService).findAll(
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
        assertThat(filter.dateFrom()).isNull();
        assertThat(filter.dateTo()).isNull();
        assertThat(filter.organizationId()).isNull();

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isZero();

        assertThat(pageable.getPageSize())
                .isEqualTo(50);

        Sort.Order createdAtOrder =
                Objects.requireNonNull(
                        pageable.getSort()
                                .getOrderFor("createdAt"),
                        "createdAt sort order must be present"
                );

        assertThat(createdAtOrder.getDirection())
                .isEqualTo(Sort.Direction.DESC);

        verifyNoMoreInteractions(
                auditEventQueryService
        );
    }

    @Test
    void findAllBindsFiltersAndPageableForSuperAdmin()
            throws Exception {
        SafeAiUserPrincipal principal =
                superAdminPrincipal();

        Instant dateFrom =
                Instant.parse("2026-06-01T00:00:00Z");

        Instant dateTo =
                Instant.parse("2026-07-01T00:00:00Z");

        mockMvc.perform(
                        get("/api/admin/audit-events")
                                .param(
                                        "eventType",
                                        AuditEventType.USER_LOGIN_SUCCESS
                                                .name()
                                )
                                .param(
                                        "userEmail",
                                        "admin@test.com"
                                )
                                .param(
                                        "userId",
                                        USER_ID.toString()
                                )
                                .param(
                                        "dateFrom",
                                        dateFrom.toString()
                                )
                                .param(
                                        "dateTo",
                                        dateTo.toString()
                                )
                                .param(
                                        "organizationId",
                                        ORGANIZATION_ID.toString()
                                )
                                .param("page", "2")
                                .param("size", "25")
                                .param(
                                        "sort",
                                        "createdAt,asc"
                                )
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isOk());

        var filterCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        AuditEventFilter.class
                );

        var pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(auditEventQueryService).findAll(
                eq(principal),
                filterCaptor.capture(),
                pageableCaptor.capture()
        );

        AuditEventFilter filter =
                filterCaptor.getValue();

        assertThat(filter.eventType())
                .isEqualTo(
                        AuditEventType.USER_LOGIN_SUCCESS
                );

        assertThat(filter.userEmail())
                .isEqualTo("admin@test.com");

        assertThat(filter.userId())
                .isEqualTo(USER_ID);

        assertThat(filter.dateFrom())
                .isEqualTo(dateFrom);

        assertThat(filter.dateTo())
                .isEqualTo(dateTo);

        assertThat(filter.organizationId())
                .isEqualTo(ORGANIZATION_ID);

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isEqualTo(2);

        assertThat(pageable.getPageSize())
                .isEqualTo(25);

        Sort.Order createdAtOrder =
                Objects.requireNonNull(
                        pageable.getSort()
                                .getOrderFor("createdAt"),
                        "createdAt sort order must be present"
                );

        assertThat(createdAtOrder.getDirection())
                .isEqualTo(Sort.Direction.ASC);

        verifyNoMoreInteractions(
                auditEventQueryService
        );
    }

    @Test
    void findByUserIdReturns200ForAdminAndPassesPathVariable()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isOk());

        var pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(auditEventQueryService).findByUserId(
                eq(USER_ID),
                eq(principal),
                pageableCaptor.capture()
        );

        Pageable pageable =
                pageableCaptor.getValue();

        assertThat(pageable.getPageNumber())
                .isZero();

        assertThat(pageable.getPageSize())
                .isEqualTo(50);

        Sort.Order createdAtOrder =
                Objects.requireNonNull(
                        pageable.getSort()
                                .getOrderFor("createdAt"),
                        "createdAt sort order must be present"
                );

        assertThat(createdAtOrder.getDirection())
                .isEqualTo(Sort.Direction.DESC);

        verifyNoMoreInteractions(
                auditEventQueryService
        );
    }

    @Test
    void findByUserIdReturns200ForSuperAdmin()
            throws Exception {
        SafeAiUserPrincipal principal =
                superAdminPrincipal();

        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/{userId}",
                                USER_ID
                        )
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isOk());

        verify(auditEventQueryService).findByUserId(
                eq(USER_ID),
                eq(principal),
                any(Pageable.class)
        );

        verifyNoMoreInteractions(
                auditEventQueryService
        );
    }

    @Test
    void findByUserIdReturns400ForInvalidUuid()
            throws Exception {
        mockMvc.perform(
                        get(
                                "/api/admin/audit-events/users/not-a-uuid"
                        )
                                .with(authentication(
                                        authToken(
                                                adminPrincipal()
                                        )
                                ))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status")
                        .value(400))
                .andExpect(jsonPath("$.error")
                        .value("BAD_REQUEST"));

        verifyNoInteractions(
                auditEventQueryService
        );
    }

    @Test
    void findAllMapsForbiddenServiceFailureTo403()
            throws Exception {
        SafeAiUserPrincipal principal =
                adminPrincipal();

        doThrow(
                new ForbiddenOperationException(
                        "Нельзя фильтровать audit "
                                + "другой организации"
                )
        ).when(auditEventQueryService).findAll(
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
                                .with(authentication(
                                        authToken(principal)
                                ))
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status")
                        .value(403))
                .andExpect(jsonPath("$.error")
                        .value("FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value(
                                "Нельзя фильтровать audit "
                                        + "другой организации"
                        ));

        verify(auditEventQueryService).findAll(
                eq(principal),
                any(AuditEventFilter.class),
                any(Pageable.class)
        );

        verifyNoMoreInteractions(
                auditEventQueryService
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

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        ADMIN_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
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
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}