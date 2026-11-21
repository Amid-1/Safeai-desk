// backend/src/test/java/ru/safeai/gateway/usage/controller/UsageExportControllerSecurityTest.java
package ru.safeai.gateway.usage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.safeai.gateway.auth.security.UserStatusFilter;
import ru.safeai.gateway.common.exception.ApiErrorResponseFactory;
import ru.safeai.gateway.common.exception.GlobalExceptionHandler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.service.UsageExportService;
import ru.safeai.gateway.usage.service.UsagePhysicalSpendService;
import ru.safeai.gateway.usage.dto.UsageProviderReportResponse;
import ru.safeai.gateway.usage.dto.UsageProviderRow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {UsageExportController.class, UsagePhysicalSpendController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserStatusFilter.class))
@Import({UsageControllerSecurityTest.TestSecurityConfig.class, UsageControllerSecurityTest.TestClockConfig.class,
        GlobalExceptionHandler.class, ApiErrorResponseFactory.class})
@ActiveProfiles("test")
class UsageExportControllerSecurityTest {
    private static final UUID ID = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final String BASE = "/api/admin/usage/exports";
    private static final String REQUEST = """
            {"kind":"SUMMARY","dateFrom":"2026-11-20T00:00:00Z","dateTo":"2026-11-21T00:00:00Z"}
            """;
    @Autowired MockMvc mvc;
    @MockitoBean UsageExportService service;
    @MockitoBean UsagePhysicalSpendService physical;
    @MockitoBean UserDetailsService userDetailsService;

    @Test
    void deniesOrdinaryUserAndAnonymousBeforeService() throws Exception {
        mvc.perform(get(BASE + "/" + ID)).andExpect(status().is4xxClientError());
        mvc.perform(get(BASE + "/" + ID).with(authentication(actor("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + ID + "/download").with(authentication(actor("ROLE_USER"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(authentication(actor("ROLE_USER"))).contentType("application/json")
                .content(REQUEST)).andExpect(status().isForbidden());
        verify(service, never()).start(any(), any());
        verify(service, never()).status(any(), any());
        verify(service, never()).download(any(), any());
    }

    @Test
    void acceptsAuthorizedJobAndReturnsNonCacheableAttachment() throws Exception {
        var ready = new UsageExportService.Status(ID, UsageExportService.State.READY,
                Instant.parse("2026-11-21T10:15:00Z"), null);
        when(service.start(any(), any())).thenReturn(ready);
        when(service.status(eq(ID), any())).thenReturn(ready);
        when(service.download(eq(ID), any())).thenReturn(new byte[]{1, 2, 3});
        mvc.perform(post(BASE).with(authentication(actor("ROLE_ADMIN"))).contentType("application/json")
                .content(REQUEST)).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(ID.toString()));
        mvc.perform(get(BASE + "/" + ID).with(authentication(actor("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READY"));
        mvc.perform(get(BASE + "/" + ID + "/download").with(authentication(actor("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"safeai-usage.csv\""))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void providerReportCarriesExactScopeAndRequiresAdministrator() throws Exception {
        String url = "/api/admin/usage/physical-attempts/providers";
        String from = "2026-11-20T00:00:00Z";
        String to = "2026-11-21T00:00:00Z";
        mvc.perform(get(url).param("dateFrom", from).param("dateTo", to)
                .with(authentication(actor("ROLE_USER")))).andExpect(status().isForbidden());
        verify(physical, never()).providerReport(any(), any(), any(), any(), any(), any());
        when(physical.providerReport(any(), any(), any(), any(), any(), any())).thenReturn(
                new UsageProviderReportResponse(from, to, ORG, null, null, "PHYSICAL_ATTEMPT_STARTED_AT",
                        List.of(new UsageProviderRow("mock", "1", "0", "0", "0",
                                "9007199254740993", "0", "0.000000000000000001"))));
        mvc.perform(get(url).param("dateFrom", from).param("dateTo", to)
                .with(authentication(actor("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organizationId").value(ORG.toString()))
                .andExpect(jsonPath("$.rows[0].input").value("9007199254740993"));
    }

    private static Authentication actor(String role) {
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(ACTOR, ORG, 1, 1,
                List.of(new SimpleGrantedAuthority(role)));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
