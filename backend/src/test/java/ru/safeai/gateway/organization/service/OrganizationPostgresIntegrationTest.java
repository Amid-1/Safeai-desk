package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
class OrganizationPostgresIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final Instant NOW =
            Instant.parse("2026-07-26T12:00:00Z");

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @BeforeEach
    void insertActor() {
        insertUser(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                true,
                "SUPER_ADMIN",
                NOW
        );
    }

    @Test
    void createResponseContainsDatabaseGeneratedTimestamps() {
        OrganizationResponse response =
                organizationService.create(
                        new CreateOrganizationRequest(
                                "Timestamp Tenant"
                        ),
                        superAdminPrincipal()
                );

        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();

        assertThat(response.updatedAt())
                .isAfterOrEqualTo(response.createdAt());
    }

    @Test
    void databaseRejectsCaseAndWhitespaceEquivalentDuplicateName() {
        jdbcTemplate.update("""
                insert into public.organizations (
                    id,
                    name,
                    enabled,
                    auth_version,
                    created_at,
                    updated_at,
                    version
                ) values (?, ?, true, 0,
                          current_timestamp,
                          current_timestamp,
                          0)
                """,
                ORGANIZATION_ID,
                "Demo Company"
        );

        assertThatThrownBy(() ->
                jdbcTemplate.update("""
                        insert into public.organizations (
                            id,
                            name,
                            enabled,
                            auth_version,
                            created_at,
                            updated_at,
                            version
                        ) values (?, ?, true, 0,
                                  current_timestamp,
                                  current_timestamp,
                                  0)
                        """,
                        UUID.randomUUID(),
                        "  demo   company "
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }

    @Test
    void concurrentDuplicateCreationProducesOneSuccessAndOneConflict()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<CreateOutcome> first = executor.submit(() -> {
                await(start);
                return createOutcome("Concurrent Tenant");
            });

            Future<CreateOutcome> second = executor.submit(() -> {
                await(start);
                return createOutcome(" concurrent   tenant ");
            });

            start.countDown();

            List<CreateOutcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );

            assertThat(outcomes)
                    .containsExactlyInAnyOrder(
                            CreateOutcome.SUCCESS,
                            CreateOutcome.CONFLICT
                    );

            Integer count = jdbcTemplate.queryForObject("""
                    select count(*)
                    from public.organizations
                    where normalized_name =
                          public.normalize_organization_name(?)
                    """,
                    Integer.class,
                    "Concurrent Tenant"
            );

            assertThat(count).isEqualTo(1);
        } finally {
            start.countDown();
        }
    }

    @Test
    void concurrentRenameIsSerializedByPessimisticLock()
            throws Exception {
        insertOrganization(
                ORGANIZATION_ID,
                "Initial Tenant",
                true
        );

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<?> first = executor.submit(() -> {
                TransactionTemplate transaction =
                        new TransactionTemplate(
                                transactionManager
                        );

                transaction.executeWithoutResult(status -> {
                    OrganizationEntity organization =
                            organizationRepository
                                    .findByIdForSecurityUpdate(
                                            ORGANIZATION_ID
                                    )
                                    .orElseThrow();

                    firstLocked.countDown();
                    await(releaseFirst);

                    organization.setName("First Rename");
                    organizationRepository.saveAndFlush(
                            organization
                    );
                });
            });

            Future<?> second = executor.submit(() -> {
                await(firstLocked);

                TransactionTemplate transaction =
                        new TransactionTemplate(
                                transactionManager
                        );

                transaction.executeWithoutResult(status -> {
                    OrganizationEntity organization =
                            organizationRepository
                                    .findByIdForSecurityUpdate(
                                            ORGANIZATION_ID
                                    )
                                    .orElseThrow();

                    organization.setName("Second Rename");
                    organizationRepository.saveAndFlush(
                            organization
                    );
                });
            });

            assertThat(firstLocked.await(
                    10,
                    TimeUnit.SECONDS
            )).isTrue();

            releaseFirst.countDown();

            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);

            String finalName = jdbcTemplate.queryForObject("""
                    select name
                    from public.organizations
                    where id = ?
                    """,
                    String.class,
                    ORGANIZATION_ID
            );

            assertThat(finalName)
                    .isEqualTo("Second Rename");
        } finally {
            releaseFirst.countDown();
            firstLocked.countDown();
        }
    }

    private CreateOutcome createOutcome(String name) {
        try {
            organizationService.create(
                    new CreateOrganizationRequest(name),
                    superAdminPrincipal()
            );
            return CreateOutcome.SUCCESS;
        } catch (ConflictException exception) {
            return CreateOutcome.CONFLICT;
        }
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                "superadmin@test.com",
                0L,
                0L,
                Set.of(
                        new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(
                                "ROLE_SUPER_ADMIN"
                        )
                )
        );
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

    private enum CreateOutcome {
        SUCCESS,
        CONFLICT
    }
}