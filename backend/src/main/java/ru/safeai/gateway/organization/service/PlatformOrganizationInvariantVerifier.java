package ru.safeai.gateway.organization.service;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.repository.UserRepository;

@Component
@Profile({"prod", "production"})
@RequiredArgsConstructor
public class PlatformOrganizationInvariantVerifier
        implements ApplicationRunner {

    private final PlatformProperties
            platformProperties;

    private final OrganizationRepository
            organizationRepository;

    private final UserRepository
            userRepository;

    @Override
    @Transactional(readOnly = true)
    public void run(
            @NonNull ApplicationArguments args
    ) {
        OrganizationEntity platform =
                organizationRepository
                        .findById(
                                platformProperties
                                        .organizationId()
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Platform organization "
                                                + "отсутствует: "
                                                + platformProperties
                                                        .organizationId()
                                )
                        );

        if (!platform.isEnabled()) {
            throw new IllegalStateException(
                    "Platform organization должна "
                            + "быть включена"
            );
        }

        if (platform.getAuthVersion() < 0L) {
            throw new IllegalStateException(
                    "Platform organization authVersion "
                            + "не может быть отрицательной"
            );
        }

        if (platform.getVersion() < 0L) {
            throw new IllegalStateException(
                    "Platform organization version "
                            + "не может быть отрицательной"
            );
        }

        long users =
                userRepository
                        .countByOrganization_Id(
                                platform.getId()
                        );

        long superAdmins =
                userRepository
                        .countByOrganizationIdAndRole(
                                platform.getId(),
                                SystemRole.SUPER_ADMIN
                                        .roleName()
                        );

        if (users < 1L || superAdmins < 1L) {
            throw new IllegalStateException(
                    "Platform organization не содержит "
                            + "SUPER_ADMIN"
            );
        }

        if (users != superAdmins) {
            throw new IllegalStateException(
                    "Platform organization должна содержать "
                            + "только SUPER_ADMIN: users="
                            + users
                            + ", superAdmins="
                            + superAdmins
            );
        }
    }
}

