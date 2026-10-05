package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelControlPlaneAccessTest {

    @Test
    void administrativeReadAllowsAdminAndSuperAdminButRejectsUser() {
        assertThatCode(() -> ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                ModelTestFixtures.adminPrincipal(), "forbidden"
        )).doesNotThrowAnyException();

        assertThatCode(() -> ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                ModelTestFixtures.superAdminPrincipal(), "forbidden"
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                ModelTestFixtures.userPrincipal(), "public-denial"
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("public-denial");
    }

    @Test
    void catalogMutationRequiresSuperAdmin() {
        assertThatCode(() -> ModelControlPlaneAccess.requireSuperAdmin(
                ModelTestFixtures.superAdminPrincipal()
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> ModelControlPlaneAccess.requireSuperAdmin(
                ModelTestFixtures.adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("SUPER_ADMIN");
    }

    @Test
    void onlySuperAdminIsCrossTenantUnrestricted() {
        assertThat(ModelControlPlaneAccess.isTenantScopeRestricted(
                ModelTestFixtures.adminPrincipal()
        )).isTrue();
        assertThat(ModelControlPlaneAccess.isTenantScopeRestricted(
                ModelTestFixtures.superAdminPrincipal()
        )).isFalse();
    }
}
