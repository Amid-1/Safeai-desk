package ru.safeai.gateway.organization.service;

import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.auth.service.UserSessionRevocationService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.organization.dto.CreateOrganizationRequest;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationImpactQueryRepository;
import ru.safeai.gateway.organization.repository.OrganizationRepository;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceAuthorizationBoundaryTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationImpactQueryRepository
            impactQueryRepository;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UserSessionRevocationService
            userSessionRevocationService;

    @Mock
    private EntityManager entityManager;

    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        organizationService =
                new OrganizationService(
                        organizationRepository,
                        impactQueryRepository,
                        auditEventService,
                        eventPublisher,
                        new PlatformProperties(
                                PLATFORM_ORGANIZATION_ID
                        ),
                        userSessionRevocationService,
                        entityManager
                );
    }

    @Test
    void ordinaryUserCannotReadOrganizationManagementViews() {
        SafeAiUserPrincipal ordinaryUser =
                ordinaryUserPrincipal();

        assertForbidden(() ->
                organizationService.findAll(
                        ordinaryUser,
                        PageRequest.of(
                                0,
                                20
                        )
                )
        );

        assertForbidden(() ->
                organizationService
                        .findCurrentOrganization(
                                ordinaryUser
                        )
        );

        assertForbidden(() ->
                organizationService.findById(
                        ORGANIZATION_ID,
                        ordinaryUser
                )
        );

        verifyNoInteractions(
                organizationRepository,
                impactQueryRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    /**
     * Regression guard: legacy generic enabled mutation must not return.
     *
     * <p>Organization lifecycle has two explicit paths:</p>
     *
     * <ul>
     *     <li>disable(...), requiring confirmationName;</li>
     *     <li>enable(...).</li>
     * </ul>
     */
    @Test
    void legacyUpdateEnabledApiIsRemoved() {
        boolean legacyMethodExists =
                Arrays.stream(
                                OrganizationService.class
                                        .getDeclaredMethods()
                        )
                        .map(
                                Method::getName
                        )
                        .anyMatch(
                                "updateEnabled"::equals
                        );

        assertThat(
                legacyMethodExists
        ).isFalse();
    }

    /**
     * Both historical index names and compatibility constraint names
     * must classify as the same organization-name uniqueness conflict.
     *
     * <p>The test verifies service behavior rather than reading the
     * private constant through reflection.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "ux_organizations_name_normalized",
            "ux_organizations_normalized_name",
            "uq_organizations_name_normalized",
            "uq_organizations_normalized_name"
    })
    void organizationNameUniqueViolationIsMappedToConflict(
            String constraintName
    ) {
        ConstraintViolationException violation =
                mock(
                        ConstraintViolationException.class
                );

        when(
                violation.getSQLState()
        ).thenReturn(
                "23505"
        );

        when(
                violation.getConstraintName()
        ).thenReturn(
                constraintName
        );

        DataIntegrityViolationException databaseException =
                new DataIntegrityViolationException(
                        "duplicate organization name",
                        violation
                );

        when(
                organizationRepository.saveAndFlush(
                        any(OrganizationEntity.class)
                )
        ).thenThrow(
                databaseException
        );

        assertThatThrownBy(() ->
                organizationService.create(
                        new CreateOrganizationRequest(
                                "Duplicate Tenant"
                        ),
                        superAdminPrincipal()
                )
        )
                .isInstanceOf(
                        ConflictException.class
                )
                .hasMessageContaining(
                        "уже существует"
                );

        verifyNoInteractions(
                impactQueryRepository,
                auditEventService,
                eventPublisher,
                userSessionRevocationService,
                entityManager
        );
    }

    private void assertForbidden(
            Runnable invocation
    ) {
        assertThatThrownBy(
                invocation::run
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "Только ADMIN или SUPER_ADMIN"
                );
    }

    private SafeAiUserPrincipal ordinaryUserPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        SUPER_ADMIN_ID,
                        PLATFORM_ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}