package ru.safeai.gateway.user.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.platform.PlatformProperties;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UserDirectoryStage2ScopeTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID foreign = UUID.randomUUID();
    private final EntityManager entityManager = mock(EntityManager.class);
    private final UserRepository repository = mock(UserRepository.class);
    private final UserDirectorySearchService service = new UserDirectorySearchService(
            entityManager, repository, new PlatformProperties(null));

    private SafeAiUserPrincipal principal(SystemRole role, UUID organization) {
        return SafeAiUserPrincipal.accessTokenPrincipal(UUID.randomUUID(), organization, 0, 0,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @Test
    void tenantCannotSearchOrCountOtherOrganization() {
        var admin = principal(SystemRole.ADMIN, tenant);
        assertThrows(ForbiddenOperationException.class,
                () -> service.search(admin, null, null, null, 0, 50, foreign));
        assertThrows(ForbiddenOperationException.class,
                () -> service.statistics(admin, foreign));
        verifyNoInteractions(entityManager, repository);
    }

    @Test
    void superAdminGetsSeparateServerCountWithinRequestedScope() {
        var admin = principal(SystemRole.SUPER_ADMIN, PlatformProperties.DEFAULT_PLATFORM_ORGANIZATION_ID);
        when(repository.countByOrganization_Id(foreign)).thenReturn(3L);
        when(repository.countByOrganizationIdAndRole(foreign, SystemRole.ADMIN.roleName())).thenReturn(1L);
        when(repository.countByOrganizationIdAndRole(foreign, SystemRole.USER.roleName())).thenReturn(2L);
        when(repository.countByOrganization_IdAndEnabled(foreign, true)).thenReturn(2L);
        var stats = service.statistics(admin, foreign);
        assertEquals(3L, stats.total());
        assertEquals(0L, stats.superAdministrators());
        assertEquals(1L, stats.disabled());
        verify(repository, never()).count();
    }

    @Test
    void globalCountIncludesPlatformSuperAdmin() {
        var admin = principal(SystemRole.SUPER_ADMIN, PlatformProperties.DEFAULT_PLATFORM_ORGANIZATION_ID);
        when(repository.count()).thenReturn(21L);
        when(repository.countByRole(SystemRole.SUPER_ADMIN.roleName())).thenReturn(1L);
        when(repository.countByRole(SystemRole.ADMIN.roleName())).thenReturn(8L);
        when(repository.countByRole(SystemRole.USER.roleName())).thenReturn(12L);
        when(repository.countByEnabled(true)).thenReturn(20L);
        var stats = service.statistics(admin, null);
        assertEquals(1L, stats.superAdministrators());
        assertEquals(stats.total(), stats.superAdministrators() + stats.administrators() + stats.users());
    }
}
