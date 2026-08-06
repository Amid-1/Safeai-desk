package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.OrganizationVersionConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.dto.OrganizationDirectoryResponse;
import ru.safeai.gateway.organization.dto.OrganizationResponse;
import ru.safeai.gateway.organization.dto.OrganizationType;
import ru.safeai.gateway.organization.dto.UpdateOrganizationRequest;
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
@org.springframework.boot.test.context.SpringBootTest
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
            Instant.parse(
                    "2026-07-26T12:00:00Z"
            );

    @Autowired
    private OrganizationService organizationService;

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
    void createReturnsCompleteDatabaseBackedContract() {
        OrganizationResponse response =
                organizationService.create(
                        new CreateOrganizationRequest(
                                "Timestamp Tenant"
                        ),
                        superAdminPrincipal()
                );

        assertThat(response.id()).isNotNull();
        assertThat(response.name())
                .isEqualTo(
                        "Timestamp Tenant"
                );
        assertThat(response.enabled()).isTrue();
        assertThat(response.type())
                .isEqualTo(
                        OrganizationType.TENANT
                );
        assertThat(
                response.protectedOrganization()
        ).isFalse();
        assertThat(response.version())
                .isZero();
        assertThat(response.createdAt())
                .isNotNull();
        assertThat(response.updatedAt())
                .isNotNull();
        assertThat(response.updatedAt())
                .isAfterOrEqualTo(
                        response.createdAt()
                );
    }

    @Test
    void databaseRejectsCaseAndWhitespaceEquivalentDuplicateName() {
        jdbcTemplate.update(
                """
                insert into public.organizations (
                    id,
                    name,
                    normalized_name,
                    enabled,
                    auth_version,
                    created_at,
                    updated_at,
                    version
                ) values (
                    ?,
                    ?,
                    public.normalize_organization_name(?),
                    true,
                    0,
                    current_timestamp,
                    current_timestamp,
                    0
                )
                """,
                ORGANIZATION_ID,
                "Demo Company",
                "Demo Company"
        );

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        insert into public.organizations (
                            id,
                            name,
                            normalized_name,
                            enabled,
                            auth_version,
                            created_at,
                            updated_at,
                            version
                        ) values (
                            ?,
                            ?,
                            public.normalize_organization_name(?),
                            true,
                            0,
                            current_timestamp,
                            current_timestamp,
                            0
                        )
                        """,
                        UUID.randomUUID(),
                        "  demo   company ",
                        "  demo   company "
                )
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }

    @Test
    void concurrentDuplicateCreationProducesOneSuccessAndOneConflict()
            throws Exception {
        CountDownLatch start =
                new CountDownLatch(1);

        try (
                ExecutorService executor =
                        Executors.newFixedThreadPool(2)
        ) {
            Future<CreateOutcome> first =
                    executor.submit(() -> {
                        await(start);
                        return createOutcome(
                                "Concurrent Tenant"
                        );
                    });

            Future<CreateOutcome> second =
                    executor.submit(() -> {
                        await(start);
                        return createOutcome(
                                " concurrent   tenant "
                        );
                    });

            start.countDown();

            List<CreateOutcome> outcomes =
                    List.of(
                            first.get(
                                    20,
                                    TimeUnit.SECONDS
                            ),
                            second.get(
                                    20,
                                    TimeUnit.SECONDS
                            )
                    );

            assertThat(outcomes)
                    .containsExactlyInAnyOrder(
                            CreateOutcome.SUCCESS,
                            CreateOutcome.CONFLICT
                    );

            Integer count =
                    jdbcTemplate.queryForObject(
                            """
                            select count(*)
                            from public.organizations
                            where normalized_name =
                                  public.normalize_organization_name(?)
                            """,
                            Integer.class,
                            "Concurrent Tenant"
                    );

            assertThat(count)
                    .isEqualTo(1);
        } finally {
            start.countDown();
        }
    }

    @Test
    void staleExpectedVersionCannotOverwriteNewerRename() {
        insertOrganization(
                ORGANIZATION_ID,
                "Initial Tenant",
                true
        );

        long version =
                organizationVersion();

        OrganizationResponse first =
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "First Rename",
                                version
                        ),
                        superAdminPrincipal()
                );

        assertThat(first.version())
                .isEqualTo(
                        version + 1L
                );

        assertThatThrownBy(() ->
                organizationService.updateName(
                        ORGANIZATION_ID,
                        new UpdateOrganizationRequest(
                                "Stale Rename",
                                version
                        ),
                        superAdminPrincipal()
                )
        ).isInstanceOf(
                OrganizationVersionConflictException.class
        );

        assertThat(
                organizationName()
        ).isEqualTo(
                "First Rename"
        );
    }

    @Test
    void concurrentRenamesWithSameVersionProduceOneSuccessAndOneConflict()
            throws Exception {
        insertOrganization(
                ORGANIZATION_ID,
                "Initial Tenant",
                true
        );

        long expectedVersion =
                organizationVersion();

        CountDownLatch start =
                new CountDownLatch(1);

        try (
                ExecutorService executor =
                        Executors.newFixedThreadPool(2)
        ) {
            Future<UpdateOutcome> first =
                    executor.submit(() -> {
                        await(start);
                        return updateOutcome(
                                "First Rename",
                                expectedVersion
                        );
                    });

            Future<UpdateOutcome> second =
                    executor.submit(() -> {
                        await(start);
                        return updateOutcome(
                                "Second Rename",
                                expectedVersion
                        );
                    });

            start.countDown();

            List<UpdateOutcome> outcomes =
                    List.of(
                            first.get(
                                    20,
                                    TimeUnit.SECONDS
                            ),
                            second.get(
                                    20,
                                    TimeUnit.SECONDS
                            )
                    );

            assertThat(outcomes)
                    .containsExactlyInAnyOrder(
                            UpdateOutcome.SUCCESS,
                            UpdateOutcome.VERSION_CONFLICT
                    );

            assertThat(
                    organizationVersion()
            ).isEqualTo(
                    expectedVersion + 1L
            );

            assertThat(
                    organizationName()
            ).isIn(
                    "First Rename",
                    "Second Rename"
            );
        } finally {
            start.countDown();
        }
    }

    @Test
    void directoryReturnsVersionAndProtectionMetadata() {
        insertOrganization(
                ORGANIZATION_ID,
                "Directory Tenant",
                true
        );

        List<OrganizationDirectoryResponse> result =
                organizationService.findDirectory(
                        "Directory",
                        20,
                        superAdminPrincipal()
                );

        assertThat(result)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id())
                            .isEqualTo(
                                    ORGANIZATION_ID
                            );
                    assertThat(item.enabled())
                            .isTrue();
                    assertThat(item.type())
                            .isEqualTo(
                                    OrganizationType.TENANT
                            );
                    assertThat(
                            item.protectedOrganization()
                    ).isFalse();
                    assertThat(item.version())
                            .isEqualTo(
                                    organizationVersion()
                            );
                });
    }

    private CreateOutcome createOutcome(
            String name
    ) {
        try {
            organizationService.create(
                    new CreateOrganizationRequest(
                            name
                    ),
                    superAdminPrincipal()
            );

            return CreateOutcome.SUCCESS;
        } catch (
                ConflictException exception
        ) {
            return CreateOutcome.CONFLICT;
        }
    }

    private UpdateOutcome updateOutcome(
            String name,
            long expectedVersion
    ) {
        try {
            organizationService.updateName(
                    ORGANIZATION_ID,
                    new UpdateOrganizationRequest(
                            name,
                            expectedVersion
                    ),
                    superAdminPrincipal()
            );

            return UpdateOutcome.SUCCESS;
        } catch (
                OrganizationVersionConflictException exception
        ) {
            return UpdateOutcome.VERSION_CONFLICT;
        }
    }

    private long organizationVersion() {
        Long version =
                jdbcTemplate.queryForObject(
                        """
                        select version
                        from public.organizations
                        where id = ?
                        """,
                        Long.class,
                        ORGANIZATION_ID
                );

        if (version == null || version < 0L) {
            throw new IllegalStateException(
                    "organization version отсутствует"
            );
        }

        return version;
    }

    private String organizationName() {
        String name =
                jdbcTemplate.queryForObject(
                        """
                        select name
                        from public.organizations
                        where id = ?
                        """,
                        String.class,
                        ORGANIZATION_ID
                );

        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "organization name отсутствует"
            );
        }

        return name;
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        "superadmin@test.com",
                        0L,
                        0L,
                        Set.of(
                                new org.springframework
                                        .security.core.authority
                                        .SimpleGrantedAuthority(
                                                "ROLE_SUPER_ADMIN"
                                        )
                        )
                );
    }

    private static void await(
            CountDownLatch latch
    ) {
        try {
            if (
                    !latch.await(
                            15,
                            TimeUnit.SECONDS
                    )
            ) {
                throw new IllegalStateException(
                        "Concurrency barrier timeout"
                );
            }
        } catch (
                InterruptedException exception
        ) {
            Thread.currentThread()
                    .interrupt();

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

    private enum UpdateOutcome {
        SUCCESS,
        VERSION_CONFLICT
    }
}