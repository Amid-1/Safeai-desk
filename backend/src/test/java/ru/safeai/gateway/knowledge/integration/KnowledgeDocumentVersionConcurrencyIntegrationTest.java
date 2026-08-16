package ru.safeai.gateway.knowledge.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.service.KnowledgeBaseService;
import ru.safeai.gateway.knowledge.service.KnowledgeDocumentService;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "safeai.knowledge.storage.type=local",
        "safeai.knowledge.storage.local-root=target/test-knowledge-concurrency"
})
class KnowledgeDocumentVersionConcurrencyIntegrationTest
        extends AbstractPostgresIntegrationTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void twoConcurrentVersionUploads_receiveDistinctSequentialNumbers()
            throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();

        insertOrganization(
                organizationId,
                "Knowledge Concurrency "
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8),
                true
        );

        insertUser(
                adminId,
                organizationId,
                "knowledge-concurrency-"
                        + UUID.randomUUID()
                                .toString()
                                .substring(0, 8)
                        + "@test.local",
                true,
                "ADMIN",
                Instant.now().minusSeconds(60)
        );

        SafeAiUserPrincipal principal = principal(
                adminId,
                organizationId
        );

        var kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(
                        "Concurrency KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION
                ),
                principal
        );

        var initial = knowledgeDocumentService.uploadNew(
                kb.id(),
                "Runbook",
                textFile("v1.txt", "v1"),
                principal
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (
                ExecutorService executor =
                        Executors.newFixedThreadPool(2)
        ) {
            Future<Integer> first = executor.submit(
                    () -> uploadConcurrent(
                            kb.id(),
                            initial.id(),
                            principal,
                            "v2-a.txt",
                            "content-a",
                            ready,
                            start
                    )
            );

            Future<Integer> second = executor.submit(
                    () -> uploadConcurrent(
                            kb.id(),
                            initial.id(),
                            principal,
                            "v2-b.txt",
                            "content-b",
                            ready,
                            start
                    )
            );

            try {
                assertThat(
                        ready.await(
                                10,
                                TimeUnit.SECONDS
                        )
                ).isTrue();

                start.countDown();

                int a = first.get(
                        30,
                        TimeUnit.SECONDS
                );

                int b = second.get(
                        30,
                        TimeUnit.SECONDS
                );

                assertThat(List.of(a, b))
                        .containsExactlyInAnyOrder(2, 3);
            } finally {
                start.countDown();
            }
        }

        Integer versionCount = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.knowledge_document_versions
                where document_id = ?
                  and organization_id = ?
                """,
                Integer.class,
                initial.id(),
                organizationId
        );

        assertThat(versionCount).isEqualTo(3);

        Integer duplicateVersionNumbers = jdbcTemplate.queryForObject(
                """
                select count(*)
                from (
                    select version_number
                    from public.knowledge_document_versions
                    where document_id = ?
                      and organization_id = ?
                    group by version_number
                    having count(*) > 1
                ) duplicates
                """,
                Integer.class,
                initial.id(),
                organizationId
        );

        assertThat(duplicateVersionNumbers).isZero();
    }

    private int uploadConcurrent(
            UUID kbId,
            UUID documentId,
            SafeAiUserPrincipal principal,
            String filename,
            String content,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);

        return knowledgeDocumentService
                .uploadVersion(
                        kbId,
                        documentId,
                        textFile(filename, content),
                        principal
                )
                .versionNumber();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrency barrier timeout"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Concurrency test interrupted",
                    exception
            );
        }
    }

    private static SafeAiUserPrincipal principal(
            UUID userId,
            UUID organizationId
    ) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                userId,
                organizationId,
                0L,
                0L,
                Set.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }

    private static MockMultipartFile textFile(
            String filename,
            String content
    ) {
        return new MockMultipartFile(
                "file",
                filename,
                "text/plain",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
