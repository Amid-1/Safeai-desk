package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformOrganizationInvariantVerifierTest {

    private static final UUID PLATFORM_ORGANIZATION_ID =
            UUID.fromString(
                    "00000000-0000-0000-0000-000000000001"
            );

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationArguments applicationArguments;

    @Test
    void rejectsMissingPlatformOrganization() {
        when(organizationRepository.findById(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                verifier().run(applicationArguments)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("отсутствует");
    }

    @Test
    void rejectsDisabledPlatformOrganization() {
        OrganizationEntity platform = platformOrganization();
        platform.setEnabled(false);

        when(organizationRepository.findById(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(Optional.of(platform));

        assertThatThrownBy(() ->
                verifier().run(applicationArguments)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("включена");
    }

    @Test
    void rejectsPlatformOrganizationWithNonSuperAdminUsers() {
        OrganizationEntity platform = platformOrganization();

        when(organizationRepository.findById(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(Optional.of(platform));

        when(userRepository.countByOrganization_Id(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(2L);

        when(userRepository.countByOrganizationIdAndRole(
                PLATFORM_ORGANIZATION_ID,
                "SUPER_ADMIN"
        )).thenReturn(1L);

        assertThatThrownBy(() ->
                verifier().run(applicationArguments)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "только SUPER_ADMIN"
                );
    }

    @Test
    void acceptsEnabledPlatformOrganizationContainingOnlySuperAdmins() {
        OrganizationEntity platform = platformOrganization();

        when(organizationRepository.findById(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(Optional.of(platform));

        when(userRepository.countByOrganization_Id(
                PLATFORM_ORGANIZATION_ID
        )).thenReturn(2L);

        when(userRepository.countByOrganizationIdAndRole(
                PLATFORM_ORGANIZATION_ID,
                "SUPER_ADMIN"
        )).thenReturn(2L);

        assertThatCode(() ->
                verifier().run(applicationArguments)
        ).doesNotThrowAnyException();
    }

    private PlatformOrganizationInvariantVerifier verifier() {
        return new PlatformOrganizationInvariantVerifier(
                new PlatformProperties(
                        PLATFORM_ORGANIZATION_ID
                ),
                organizationRepository,
                userRepository
        );
    }

    private OrganizationEntity platformOrganization() {
        OrganizationEntity platform =
                new OrganizationEntity();

        platform.setId(PLATFORM_ORGANIZATION_ID);
        platform.setName("SafeAI Platform");
        platform.setEnabled(true);
        platform.setAuthVersion(0L);

        return platform;
    }
}
