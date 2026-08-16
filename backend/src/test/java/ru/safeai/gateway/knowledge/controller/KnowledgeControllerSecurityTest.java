package ru.safeai.gateway.knowledge.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                KnowledgeBaseController.class,
                KnowledgeDocumentController.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = UserStatusFilter.class
        )
)
@Import({
        KnowledgeControllerSecurityTest.TestSecurityConfig.class,
        GlobalExceptionHandler.class,
        ApiErrorResponseFactory.class
})
@ActiveProfiles("test")
class KnowledgeControllerSecurityTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID KB_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final UUID DOCUMENT_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final UUID VERSION_ID =
            UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
            );

    private static final Instant NOW =
            Instant.parse("2026-08-16T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private KnowledgeDocumentService knowledgeDocumentService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

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

    @Test
    void listWithoutAuthentication_returns4xx()
            throws Exception {
        mockMvc.perform(
                        get("/api/knowledge-bases")
                )
                .andExpect(status().is4xxClientError());

        verify(
                knowledgeBaseService,
                never()
        ).findAll(
                any(SafeAiUserPrincipal.class),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void listWithUserRole_returns200()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_USER");

        when(knowledgeBaseService.findAll(
                eq(currentUser),
                eq(0),
                eq(50)
        )).thenReturn(
                new org.springframework.data.domain.PageImpl<>(
                        List.of(
                                knowledgeBaseResponse()
                        )
                )
        );

        mockMvc.perform(
                        get("/api/knowledge-bases")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[0].id")
                                .value(KB_ID.toString())
                )
                .andExpect(
                        jsonPath("$.content[0].name")
                                .value("Production Runbooks")
                );
    }

    @Test
    void createWithUserRole_returns403AndDoesNotCallService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_USER");

        mockMvc.perform(
                        post("/api/knowledge-bases")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "Forbidden",
                                          "description": null,
                                          "visibility": "ORGANIZATION"
                                        }
                                        """
                                )
                )
                .andExpect(status().isForbidden());

        verify(
                knowledgeBaseService,
                never()
        ).create(
                any(CreateKnowledgeBaseRequest.class),
                any(SafeAiUserPrincipal.class)
        );
    }

    @Test
    void createWithAdminRole_reachesService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_ADMIN");

        when(knowledgeBaseService.create(
                any(CreateKnowledgeBaseRequest.class),
                eq(currentUser)
        )).thenReturn(
                knowledgeBaseResponse()
        );

        mockMvc.perform(
                        post("/api/knowledge-bases")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "name": "Production Runbooks",
                                          "description": "Docs",
                                          "visibility": "ORGANIZATION"
                                        }
                                        """
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(KB_ID.toString())
                );

        verify(knowledgeBaseService).create(
                any(CreateKnowledgeBaseRequest.class),
                eq(currentUser)
        );
    }

    @Test
    void superAdminIsNotImplicitlyAllowedIntoTenantKnowledgeDataPlane()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_SUPER_ADMIN");

        mockMvc.perform(
                        get("/api/knowledge-bases")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(status().isForbidden());

        verify(
                knowledgeBaseService,
                never()
        ).findAll(
                any(SafeAiUserPrincipal.class),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void invalidPageSize_returns400BeforeService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_USER");

        mockMvc.perform(
                        get("/api/knowledge-bases")
                                .param("size", "101")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        verify(
                knowledgeBaseService,
                never()
        ).findAll(
                any(SafeAiUserPrincipal.class),
                anyInt(),
                anyInt()
        );
    }

    @Test
    void multipartUploadWithUserRole_returns201WhenServiceAuthorizesEditor()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_USER");

        when(knowledgeDocumentService.uploadNew(
                eq(KB_ID),
                eq("Runbook"),
                any(),
                eq(currentUser)
        )).thenReturn(documentResponse());

        mockMvc.perform(
                        multipart(
                                "/api/knowledge-bases/{knowledgeBaseId}/documents",
                                KB_ID
                        )
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "runbook.txt",
                                                "text/plain",
                                                "hello".getBytes(StandardCharsets.UTF_8)
                                        )
                                )
                                .param("name", "Runbook")
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(DOCUMENT_ID.toString())
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("PENDING")
                );
    }

    @Test
    void documentListWithUserRole_returns200()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal("ROLE_USER");

        when(knowledgeDocumentService.list(
                KB_ID,
                currentUser,
                0,
                50
        )).thenReturn(
                new KnowledgeDocumentPageResponse(
                        List.of(documentResponse()),
                        0,
                        50,
                        1,
                        1
                )
        );

        mockMvc.perform(
                        get(
                                "/api/knowledge-bases/{knowledgeBaseId}/documents",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(currentUser)
                                        )
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[0].id")
                                .value(DOCUMENT_ID.toString())
                );
    }

    private static Authentication authToken(
            SafeAiUserPrincipal principal
    ) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    private static SafeAiUserPrincipal principal(
            String role
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(role)
                )
        );
    }

    private static KnowledgeBaseResponse knowledgeBaseResponse() {
        return new KnowledgeBaseResponse(
                KB_ID,
                ORGANIZATION_ID,
                "Production Runbooks",
                "Docs",
                KnowledgeBaseVisibility.ORGANIZATION,
                true,
                USER_ID,
                0L,
                NOW,
                NOW
        );
    }

    private static KnowledgeDocumentResponse documentResponse() {
        return new KnowledgeDocumentResponse(
                DOCUMENT_ID,
                KB_ID,
                "Runbook",
                true,
                0L,
                VERSION_ID,
                1,
                "runbook.txt",
                "text/plain",
                5L,
                KnowledgeIngestionStatus.PENDING,
                NOW,
                NOW
        );
    }
}
