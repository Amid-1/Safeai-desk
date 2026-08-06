
package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.repository.AuditDirectoryQueryRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditDirectoryServiceTest {

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID FOREIGN_ORGANIZATION_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    @Mock
    private AuditDirectoryQueryRepository repository;

    private AuditDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new AuditDirectoryService(
                repository
        );
    }

    @Test
    void eventTypesContainEveryBackendEnumValue() {
        assertThat(service.findEventTypes())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(
                                        AuditEventType.values()
                                )
                                .map(Enum::name)
                                .toList()
                );
    }

    @Test
    void adminActorDirectoryIsAlwaysScopedToOwnOrganization() {
        service.findActors(
                adminPrincipal(),
                " ADMIN@Test.COM ",
                null,
                20
        );

        verify(repository).findActors(
                ORGANIZATION_ID,
                "admin@test.com",
                20
        );
    }

    @Test
    void adminCannotRequestForeignActorDirectory() {
        assertThatThrownBy(() ->
                service.findActors(
                        adminPrincipal(),
                        null,
                        FOREIGN_ORGANIZATION_ID,
                        20
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessageContaining(
                        "другой организации"
                );

        verifyNoInteractions(repository);
    }

    @Test
    void superAdminMaySearchAcrossAllTargetOrganizations() {
        service.findActors(
                superAdminPrincipal(),
                "test",
                null,
                50
        );

        verify(repository).findActors(
                null,
                "test",
                50
        );
    }

    @Test
    void targetOrganizationDirectoryRejectsAdmin() {
        assertThatThrownBy(() ->
                service.findTargetOrganizations(
                        adminPrincipal(),
                        null,
                        20
                )
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                );

        verifyNoInteractions(repository);
    }

    @Test
    void targetOrganizationDirectoryAllowsSuperAdmin() {
        service.findTargetOrganizations(
                superAdminPrincipal(),
                " SafeAI ",
                20
        );

        verify(repository)
                .findTargetOrganizations(
                        "safeai",
                        20
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
                        Set.of(
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
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                );
    }
}
