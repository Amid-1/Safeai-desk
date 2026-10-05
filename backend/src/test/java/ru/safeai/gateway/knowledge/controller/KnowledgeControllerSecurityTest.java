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
import ru.safeai.gateway.knowledge.dto.AnswerPassportResponse;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentPageResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeDocumentResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalResponse;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;
import ru.safeai.gateway.knowledge.rag.AnswerPassportService;
import ru.safeai.gateway.knowledge.rag.KnowledgeMode;
import ru.safeai.gateway.knowledge.service.KnowledgeAccessService;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.knowledge.service.KnowledgeEvaluationService;
import ru.safeai.gateway.knowledge.service.KnowledgeOperationsService;
import ru.safeai.gateway.knowledge.service.KnowledgeRetrievalService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                AnswerPassportController.class,
                KnowledgeBaseController.class,
                KnowledgeDocumentController.class,
                KnowledgeEvaluationController.class,
                KnowledgeRetrievalController.class
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

    private static final UUID CHAT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID TURN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID RETRIEVAL_RUN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID PASSPORT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final UUID ROUTE_DECISION_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
            );

    private static final UUID EVALUATION_RUN_ID =
            UUID.fromString(
                    "66666666-6666-6666-6666-666666666666"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-16T12:00:00Z"
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private KnowledgeAccessService knowledgeAccessService;

    @MockitoBean
    private KnowledgeDocumentService knowledgeDocumentService;

    @MockitoBean
    private KnowledgeOperationsService knowledgeOperationsService;

    @MockitoBean
    private KnowledgeRetrievalService knowledgeRetrievalService;

    @MockitoBean
    private KnowledgeEvaluationService knowledgeEvaluationService;

    @MockitoBean
    private AnswerPassportService answerPassportService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @TestConfiguration
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
                            AbstractHttpConfigurer::disable
                    )
                    .authorizeHttpRequests(
                            authorize ->
                                    authorize
                                            .anyRequest()
                                            .authenticated()
                    )
                    .build();
        }
    }

    @Test
    void listWithoutAuthentication_returns4xx() throws Exception {
        mockMvc.perform(
                        get(
                                "/api/knowledge-bases"
                        )
                )
                .andExpect(
                        status().is4xxClientError()
                );

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
    void listWithUserRole_returns200() throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        when(
                knowledgeBaseService.findAll(
                        eq(currentUser),
                        eq(0),
                        eq(50)
                )
        ).thenReturn(
                new org.springframework.data.domain.PageImpl<>(
                        List.of(
                                knowledgeBaseResponse()
                        )
                )
        );

        mockMvc.perform(
                        get(
                                "/api/knowledge-bases"
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].id"
                        ).value(
                                KB_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].name"
                        ).value(
                                "Production Runbooks"
                        )
                );
    }

    @Test
    void effectiveAccessReturnsCurrentUsersCapabilities()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        KnowledgeBaseEntity knowledgeBase =
                knowledgeBaseEntity();

        when(
                knowledgeAccessService.requireAccess(
                        KB_ID,
                        currentUser,
                        KnowledgeBaseAccessLevel.VIEWER
                )
        ).thenReturn(
                new KnowledgeAccessService.Access(
                        knowledgeBase,
                        KnowledgeBaseAccessLevel.EDITOR,
                        false
                )
        );

        mockMvc.perform(
                        get(
                                "/api/knowledge-bases/{knowledgeBaseId}/access",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.knowledgeBaseId"
                        ).value(
                                KB_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.accessLevel"
                        ).value(
                                "EDITOR"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.canEditDocuments"
                        ).value(
                                Boolean.TRUE
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.canManageBase"
                        ).value(
                                Boolean.FALSE
                        )
                );
    }

    @Test
    void createWithUserRole_returns403AndDoesNotCallService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases"
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
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
                .andExpect(
                        status().isForbidden()
                );

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
                principal(
                        "ROLE_ADMIN"
                );

        when(
                knowledgeBaseService.create(
                        any(CreateKnowledgeBaseRequest.class),
                        eq(currentUser)
                )
        ).thenReturn(
                knowledgeBaseResponse()
        );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases"
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
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
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.id"
                        ).value(
                                KB_ID.toString()
                        )
                );

        verify(
                knowledgeBaseService
        ).create(
                any(CreateKnowledgeBaseRequest.class),
                eq(currentUser)
        );
    }

    @Test
    void superAdminIsNotImplicitlyAllowedIntoTenantKnowledgeDataPlane()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_SUPER_ADMIN"
                );

        mockMvc.perform(
                        get(
                                "/api/knowledge-bases"
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );

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
                principal(
                        "ROLE_USER"
                );

        mockMvc.perform(
                        get(
                                "/api/knowledge-bases"
                        )
                                .param(
                                        "size",
                                        "101"
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );

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
                principal(
                        "ROLE_USER"
                );

        when(
                knowledgeDocumentService.uploadNew(
                        eq(KB_ID),
                        eq("Runbook"),
                        any(),
                        eq(currentUser)
                )
        ).thenReturn(
                documentResponse()
        );

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
                                                "hello".getBytes(
                                                        StandardCharsets.UTF_8
                                                )
                                        )
                                )
                                .param(
                                        "name",
                                        "Runbook"
                                )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath(
                                "$.id"
                        ).value(
                                DOCUMENT_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.currentVersionId"
                        ).value(
                                VERSION_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.status"
                        ).value(
                                "PENDING"
                        )
                );
    }

    @Test
    void documentListWithUserRole_returns200()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        when(
                knowledgeDocumentService.list(
                        KB_ID,
                        currentUser,
                        0,
                        50
                )
        ).thenReturn(
                new KnowledgeDocumentPageResponse(
                        List.of(
                                documentResponse()
                        ),
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
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].id"
                        ).value(
                                DOCUMENT_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.content[0].currentVersionId"
                        ).value(
                                VERSION_ID.toString()
                        )
                );
    }

    @Test
    void pendingDocumentFixtureKeepsUploadedAndSearchableVersionsDistinct() {
        KnowledgeDocumentResponse response =
                documentResponse();

        assertThat(
                response.currentVersionId()
        ).isEqualTo(
                VERSION_ID
        );

        assertThat(
                response.searchableVersionId()
        ).isNull();

        assertThat(
                response.status()
        ).isEqualTo(
                KnowledgeIngestionStatus.PENDING
        );
    }

    @Test
    void retrievalWithUserRole_reachesServiceAndPreservesPrincipal()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        when(
                knowledgeRetrievalService.retrieve(
                        eq(KB_ID),
                        any(KnowledgeRetrievalRequest.class),
                        eq(currentUser)
                )
        ).thenReturn(
                retrievalResponse()
        );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases/{knowledgeBaseId}/retrieval",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "query": "evidence",
                                          "topK": 5
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.retrievalRunId"
                        ).value(
                                RETRIEVAL_RUN_ID.toString()
                        )
                );

        verify(
                knowledgeRetrievalService
        ).retrieve(
                eq(KB_ID),
                argThat(
                        request ->
                                "evidence".equals(
                                        request.query()
                                )
                                        && Integer.valueOf(5).equals(
                                        request.topK()
                                )
                ),
                eq(currentUser)
        );
    }

    @Test
    void retrievalWithInvalidPayload_returns400BeforeService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases/{knowledgeBaseId}/retrieval",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "query": " ",
                                          "topK": 101
                                        }
                                        """
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );

        verifyNoInteractions(
                knowledgeRetrievalService
        );
    }

    @Test
    void evaluationWithUserRole_returns403AndDoesNotCallService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases/{knowledgeBaseId}/evaluations",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        validEvaluationJson()
                                )
                )
                .andExpect(
                        status().isForbidden()
                );

        verifyNoInteractions(
                knowledgeEvaluationService
        );
    }

    @Test
    void evaluationWithAdminRole_reachesService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_ADMIN"
                );

        when(
                knowledgeEvaluationService.evaluate(
                        eq(KB_ID),
                        any(KnowledgeEvaluationRequest.class),
                        eq(currentUser)
                )
        ).thenReturn(
                evaluationResponse()
        );

        mockMvc.perform(
                        post(
                                "/api/knowledge-bases/{knowledgeBaseId}/evaluations",
                                KB_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        validEvaluationJson()
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.evaluationRunId"
                        ).value(
                                EVALUATION_RUN_ID.toString()
                        )
                );

        verify(
                knowledgeEvaluationService
        ).evaluate(
                eq(KB_ID),
                argThat(
                        request ->
                                "security-smoke".equals(
                                        request.datasetName()
                                )
                                        && Integer.valueOf(5).equals(
                                        request.topK()
                                )
                                        && request.cases().size() == 1
                ),
                eq(currentUser)
        );
    }

    @Test
    void answerPassportWithUserRole_reachesPrincipalScopedService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_USER"
                );

        when(
                answerPassportService.requireByTurn(
                        CHAT_ID,
                        TURN_ID,
                        currentUser
                )
        ).thenReturn(
                answerPassportResponse()
        );

        mockMvc.perform(
                        get(
                                "/api/chats/{chatId}/turns/{turnId}/answer-passport",
                                CHAT_ID,
                                TURN_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.chatTurnId"
                        ).value(
                                TURN_ID.toString()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.knowledgeBaseId"
                        ).value(
                                KB_ID.toString()
                        )
                );

        verify(
                answerPassportService
        ).requireByTurn(
                CHAT_ID,
                TURN_ID,
                currentUser
        );
    }

    @Test
    void answerPassportWithSuperAdminRole_isDeniedBeforeService()
            throws Exception {
        SafeAiUserPrincipal currentUser =
                principal(
                        "ROLE_SUPER_ADMIN"
                );

        mockMvc.perform(
                        get(
                                "/api/chats/{chatId}/turns/{turnId}/answer-passport",
                                CHAT_ID,
                                TURN_ID
                        )
                                .with(
                                        authentication(
                                                authToken(
                                                        currentUser
                                                )
                                        )
                                )
                )
                .andExpect(
                        status().isForbidden()
                );

        verifyNoInteractions(
                answerPassportService
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
                        new SimpleGrantedAuthority(
                                role
                        )
                )
        );
    }

    private static KnowledgeBaseEntity knowledgeBaseEntity() {
        KnowledgeBaseEntity knowledgeBase =
                new KnowledgeBaseEntity();

        knowledgeBase.setId(
                KB_ID
        );
        knowledgeBase.setOrganizationId(
                ORGANIZATION_ID
        );
        knowledgeBase.setName(
                "Production Runbooks"
        );
        knowledgeBase.setVisibility(
                KnowledgeBaseVisibility.MEMBERS
        );
        knowledgeBase.setEnabled(
                true
        );
        knowledgeBase.setCreatedByUserId(
                USER_ID
        );

        return knowledgeBase;
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
                null,
                1,
                "runbook.txt",
                "text/plain",
                5L,
                KnowledgeIngestionStatus.PENDING,
                NOW,
                NOW
        );
    }

    private static KnowledgeRetrievalResponse retrievalResponse() {
        return new KnowledgeRetrievalResponse(
                RETRIEVAL_RUN_ID,
                KB_ID,
                "a".repeat(64),
                "hashing-v1",
                NOW,
                List.of()
        );
    }

    private static KnowledgeEvaluationResponse evaluationResponse() {
        return new KnowledgeEvaluationResponse(
                EVALUATION_RUN_ID,
                KB_ID,
                "security-smoke",
                5,
                1.0d,
                1.0d,
                1.0d,
                NOW,
                List.of()
        );
    }

    private static AnswerPassportResponse answerPassportResponse() {
        return new AnswerPassportResponse(
                PASSPORT_ID,
                TURN_ID,
                RETRIEVAL_RUN_ID,
                KB_ID,
                ROUTE_DECISION_ID,
                KnowledgeMode.KNOWLEDGE_ONLY,
                "openai",
                "gpt-4.1",
                "gpt-4.1",
                "hashing-v1",
                "b".repeat(64),
                "c".repeat(64),
                true,
                true,
                NOW,
                List.of()
        );
    }

    private static String validEvaluationJson() {
        return """
                {
                  "datasetName": "security-smoke",
                  "topK": 5,
                  "cases": [
                    {
                      "query": "evidence",
                      "expectedDocumentVersionIds": [
                        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                      ]
                    }
                  ]
                }
                """;
    }
}
