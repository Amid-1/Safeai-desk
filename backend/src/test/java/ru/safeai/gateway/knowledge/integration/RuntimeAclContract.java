package ru.safeai.gateway.knowledge.integration;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.SafeaiBackendApplication;
import ru.safeai.gateway.audit.service.AuditOutboxProcessor;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionClaim;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionProcessor;
import ru.safeai.gateway.knowledge.ingestion.KnowledgeIngestionQueueRepository;
import ru.safeai.gateway.testsupport.SafeAiJacksonJsonPathExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SafeaiBackendApplication.class, properties = {
        "spring.datasource.url=${SAFEAI_CONTRACT_JDBC_URL}",
        "spring.datasource.username=safeai_app",
        "spring.datasource.password=${SAFEAI_CONTRACT_DB_PASSWORD}",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=${SAFEAI_CONTRACT_REDIS_PORT}",
        "spring.data.redis.password=",
        "safeai.environment=local",
        "safeai.ai.provider=mock",
        "safeai.knowledge.embedding.provider=hashing",
        "safeai.knowledge.ocr.provider=disabled",
        "safeai.knowledge.storage.type=local",
        "safeai.knowledge.storage.local-root=${SAFEAI_CONTRACT_STORAGE}",
        "safeai.knowledge.ingestion.enabled=false",
        "safeai.knowledge.storage-reconciliation.enabled=false",
        "safeai.knowledge.generation.gc.enabled=false",
        "safeai.knowledge.deletion.accept-enabled=false",
        "safeai.knowledge.deletion.executor-enabled=false",
        "safeai.knowledge.base-deletion.accept-enabled=false",
        "safeai.knowledge.base-deletion.executor-enabled=false",
        "safeai.knowledge.erasure-journal.enabled=false",
        "safeai.chat.erasure.accept-enabled=false",
        "safeai.chat.erasure.executor-enabled=false",
        "safeai.chat.erasure.retention-approved=false",
        "safeai.audit.retention.enabled=false",
        "safeai.audit.outbox.poll-delay-ms=3600000",
        "safeai.usage.rollup.enabled=false",
        "safeai.chat.recovery.enabled=false",
        "safeai.auth.refresh-cleanup.cron=-",
        "safeai.security.user-status-cache.enabled=false",
        "safeai.rate-limit.login.enabled=true",
        "safeai.rate-limit.refresh.enabled=true",
        "safeai.rate-limit.ai-messages.enabled=true"
})
@ActiveProfiles("test")
@ExtendWith(SafeAiJacksonJsonPathExtension.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
class RuntimeAclContract {
    static {
        String url = System.getenv("SAFEAI_CONTRACT_JDBC_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/safeai_contract_(clean|upgrade)")) {
            throw new IllegalStateException("Only an isolated runtime contract database is permitted");
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PasswordEncoder passwords;
    @Autowired KnowledgeIngestionQueueRepository queue;
    @Autowired KnowledgeIngestionProcessor ingestion;
    @Autowired AuditOutboxProcessor audit;

    record Actor(UUID id, UUID org, String email, String password) {}
    record Session(Cookie access, Cookie refresh) {}

    @Test
    void realSpringServicesWithRestrictedAppLogin() throws Exception {
        assertEquals("safeai_app:safeai_app:" + System.getenv("SAFEAI_CONTRACT_JDBC_URL").split("/")[3],
                jdbc.queryForObject("SELECT session_user||':'||current_user||':'||current_database()",
                        String.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname=current_user",
                Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_schema_privilege(current_user,'public','CREATE')", Boolean.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT has_table_privilege(current_user,'public.flyway_schema_history','SELECT')", Boolean.class));
        System.out.println("STAGE5 IDENTITY PASS: real safeai_app Spring JDBC, Hibernate validate, no Flyway");

        Actor a = fixture(), b = fixture();
        Session sa = login(a), sb = login(b);
        mvc.perform(get("/api/auth/me").cookie(sa.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a.id().toString()));
        mvc.perform(get("/api/auth/me").cookie(sb.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(b.id().toString()));
        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(sa.refresh()).with(realCsrf()))
                .andExpect(status().isNoContent()).andReturn();
        sa = session(refreshed);
        mvc.perform(get("/api/auth/me").cookie(sa.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a.id().toString()));
        System.out.println("STAGE5 AUTH PASS: two tenants, login, refresh rotation, me");

        UUID chat = id(mvc.perform(json(post("/api/chats"), sa, "{\"title\":\"Stage5 synthetic chat\"}"))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(get("/api/chats/{chatId}", chat).cookie(sa.access())).andExpect(status().isOk());
        denied(mvc.perform(get("/api/chats/{chatId}", chat).cookie(sb.access())).andReturn());
        UUID request = UUID.randomUUID();
        String message = "{\"content\":\"Stage5 synthetic question\",\"clientRequestId\":\"" + request + "\"}";
        mvc.perform(json(post("/api/chats/{id}/messages", chat), sa, message))
                .andExpect(status().isForbidden());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT count(*) FROM public.model_route_decisions
                WHERE organization_id=? AND outcome='DENIED' AND reason='MODEL_NOT_ALLOWED' AND policy_id IS NULL
                """, Long.class, a.org()));
        allowMockModel(a);
        request = UUID.randomUUID();
        message = "{\"content\":\"Stage5 synthetic question\",\"clientRequestId\":\"" + request + "\"}";
        mvc.perform(json(post("/api/chats/{id}/messages", chat), sa, message))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.assistantMessage").exists());
        mvc.perform(json(post("/api/chats/{id}/messages", chat), sa, message))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replay").value(true));
        mvc.perform(post("/api/chats/{id}/archive", chat).cookie(sa.access()).with(realCsrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/chats/{id}/restore", chat).cookie(sa.access()).with(realCsrf()))
                .andExpect(status().isNoContent());
        System.out.println("STAGE5 CHAT PASS: create, read, message, idempotency, archive, restore, tenant denial");

        MvcResult createdBase = mvc.perform(json(post("/api/knowledge-bases"), sa,
                "{\"name\":\"Stage5 synthetic KB\",\"description\":\"Isolated test\",\"visibility\":\"ORGANIZATION\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID base = id(createdBase);
        assertEquals("/api/knowledge-bases/" + base, createdBase.getResponse().getHeader("Location"));
        mvc.perform(get("/api/knowledge-bases/{id}", base).cookie(sa.access())).andExpect(status().isOk());
        denied(mvc.perform(get("/api/knowledge-bases/{id}", base).cookie(sb.access())).andReturn());
        // Existing snapshot objects are not mounted. Do not let claimNext choose an old job.
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM public.knowledge_ingestion_jobs WHERE attempt < 5
                  AND ((status='PENDING' AND next_attempt_at <= clock_timestamp())
                  OR (status IN ('VALIDATING','EXTRACTING','CHUNKING') AND lease_until < clock_timestamp()))
                """, Long.class), "Snapshot has claimable jobs unrelated to Stage5; stop before upload");
        MockMultipartFile file = new MockMultipartFile("file", "stage5.txt", "text/plain",
                ("Stage five synthetic document. The SafeAI test codeword is amber lantern. "
                        + "This isolated test checks document indexing and retrieval.").getBytes(StandardCharsets.UTF_8));
        UUID doc = id(mvc.perform(multipart("/api/knowledge-bases/{id}/documents", base).file(file)
                        .param("name", "Stage5 document").cookie(sa.access()).with(realCsrf()))
                .andExpect(status().isCreated()).andReturn());
        Instant now = Instant.now();
        KnowledgeIngestionClaim claim = queue.claimNext(now, now.plusSeconds(180), 5)
                .orElseThrow(() -> new AssertionError("No claimable synthetic ingestion job"));
        assertEquals(doc, claim.documentId(), "Never process a pre-existing snapshot job");
        ingestion.process(claim);
        assertEquals("READY", jdbc.queryForObject(
                "SELECT status FROM public.knowledge_ingestion_jobs WHERE id=?", String.class, claim.jobId()),
                "Ingestion must reach READY; inspect SQLSTATE / last_error_code on failure");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM public.knowledge_document_chunks WHERE document_version_id=?",
                Long.class, claim.documentVersionId()) > 0);
        mvc.perform(json(post("/api/knowledge-bases/{id}/retrieval", base), sa,
                "{\"query\":\"amber lantern\",\"topK\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hits").isNotEmpty());
        String rag = "{\"content\":\"What is the test codeword?\",\"clientRequestId\":\""
                + UUID.randomUUID() + "\",\"knowledgeBaseId\":\"" + base + "\",\"knowledgeMode\":\"KNOWLEDGE_ASSISTED\"}";
        mvc.perform(json(post("/api/chats/{id}/messages", chat), sa, rag))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
        System.out.println("STAGE5 KNOWLEDGE PASS: tenant denial, upload, ingestion, chunks, retrieval, RAG");

        mvc.perform(get("/api/admin/usage/summary").cookie(sa.access())).andExpect(status().isOk());
        AuditOutboxProcessor.BatchResult processed = audit.processBatch();
        assertEquals(0, processed.failed(), "Audit outbox delivery failed");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM public.audit_events WHERE actor_user_id=?",
                Long.class, a.id()) > 0, "Expected durable fixture audit events");
        mvc.perform(get("/api/admin/audit-events").cookie(sa.access())).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").cookie(sa.refresh(), sa.access()).with(realCsrf()))
                .andExpect(status().isNoContent());
        System.out.println("STAGE5 AUDIT/USAGE PASS: real read and delivery");
        System.out.println("STAGE5 BACKEND RUNTIME PASS");
    }

    private Actor fixture() {
        UUID id = UUID.randomUUID(), org = UUID.randomUUID();
        Actor actor = new Actor(id, org, "stage5-" + id + "@example.invalid", "Stage5-" + UUID.randomUUID() + "!");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String name = "Stage5 " + org;
            assertEquals(1, jdbc.update("""
                    INSERT INTO public.organizations(id,name,normalized_name,enabled,created_at,updated_at,version)
                    VALUES (?,?,public.normalize_organization_name(?),true,clock_timestamp(),clock_timestamp(),0)
                    """, org, name, name));
            assertEquals(1, jdbc.update("""
                    INSERT INTO public.users(id,organization_id,email,password_hash,full_name,enabled,
                      disabled_at,created_at,updated_at,token_version,version)
                    VALUES (?,?,?,?,?,true,NULL,clock_timestamp(),clock_timestamp(),0,0)
                    """, id, org, actor.email(), passwords.encode(actor.password()), "Stage5 synthetic admin"));
            assertEquals(1, jdbc.update("""
                    INSERT INTO public.user_roles(user_id,role_id)
                    SELECT ?,id FROM public.roles WHERE name='ADMIN'
                    """, id));
        });
        return actor;
    }

    private void allowMockModel(Actor actor) {
        Timestamp effective = Timestamp.from(Instant.now().minusSeconds(5));
        assertEquals(1, jdbc.update("""
                INSERT INTO public.model_catalog_entries (
                    id,model_key,version,provider,provider_model_id,display_name,lifecycle,
                    max_input_tokens,max_output_tokens,retention_status,training_use_status,
                    pricing_status,pricing_complete,input_usd_per_1m_tokens,output_usd_per_1m_tokens,
                    pricing_version,pricing_scheme,effective_from,source,created_by_user_id,created_at)
                VALUES (?,'mock:mock-safeai',1,'mock','mock-safeai','Mock SafeAI','ACTIVE',
                    64000,2048,'NOT_DECLARED','NOT_DECLARED','FREE',true,0,0,
                    'mock-2026-01','FLAT_TOKEN_V1',?,'RUNTIME_IMPORT',?,?)
                """, UUID.randomUUID(), effective, actor.id(), effective));
        assertEquals(1, jdbc.update("""
                INSERT INTO public.organization_model_policies (
                    id,organization_id,version,enabled,allow_model_keys,deny_model_keys,default_model_key,
                    max_input_tokens,max_output_tokens,max_request_cost_usd,monthly_budget_usd,
                    budget_enforcement,require_complete_pricing,require_no_training,require_zero_data_retention,
                    created_by_user_id,created_at)
                VALUES (?,?,1,true,ARRAY['mock:mock-safeai']::text[],'{}'::text[],NULL,
                    NULL,NULL,NULL,NULL,'SOFT',false,false,false,?,?)
                """, UUID.randomUUID(), actor.org(), actor.id(), effective));
    }

    private RequestPostProcessor realCsrf() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie token = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(token, "Missing CSRF cookie");
        return request -> {
            Cookie[] existing = request.getCookies();
            Cookie[] cookies = existing == null ? new Cookie[1]
                    : java.util.Arrays.copyOf(existing, existing.length + 1);
            cookies[cookies.length - 1] = token;
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    private Session login(Actor a) throws Exception {
        return session(mvc.perform(post("/api/auth/login").with(realCsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + a.email() + "\",\"password\":\"" + a.password() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a.id().toString())).andReturn());
    }
    private Session session(MvcResult result) {
        return new Session(cookie(result, "safeai-access"), cookie(result, "safeai-refresh"));
    }
    private Cookie cookie(MvcResult result, String name) {
        String prefix = name + "=";
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(prefix)) {
                String value = header.substring(prefix.length()).split(";", 2)[0];
                if (!value.isEmpty()) return new Cookie(name, value);
            }
        }
        throw new AssertionError("Missing issued cookie " + name);
    }
    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Session s, String body) throws Exception {
        return request.cookie(s.access()).with(realCsrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private UUID id(MvcResult result) throws Exception {
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return UUID.fromString(id);
    }
    private void denied(MvcResult result) {
        int status = result.getResponse().getStatus();
        assertTrue(status == 403 || status == 404, "Cross-tenant access must return 403/404, got " + status);
    }
}
