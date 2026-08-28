package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.dto.AuditEventFilter;
import ru.safeai.gateway.audit.repository.AuditDirectoryQueryRepository;
import ru.safeai.gateway.audit.repository.AuditEventRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditServiceAuthorizationBoundaryTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private AuditDirectoryQueryRepository directoryRepository;

    private AuditEventQueryService queryService;
    private AuditEventCursorService cursorService;
    private AuditDirectoryService directoryService;

    @BeforeEach
    void setUp() {
        queryService =
                new AuditEventQueryService(
                        auditEventRepository
                );

        cursorService =
                new AuditEventCursorService(
                        auditEventRepository,
                        new AuditCursorCodec(),
                        queryService
                );

        directoryService =
                new AuditDirectoryService(
                        directoryRepository
                );
    }

    @Test
    void roleUserDirectAuditServiceCallsAreForbiddenBeforeRepositoryAccess() {
        SafeAiUserPrincipal user =
                userPrincipal();

        assertForbidden(() ->
                queryService.findAll(
                        user,
                        emptyFilter(),
                        PageRequest.of(
                                0,
                                50
                        )
                )
        );

        assertForbidden(() ->
                cursorService.findAll(
                        user,
                        emptyFilter(),
                        null,
                        50
                )
        );

        assertForbidden(() ->
                directoryService.findActors(
                        user,
                        null,
                        null,
                        20
                )
        );

        verifyNoInteractions(
                auditEventRepository,
                directoryRepository
        );
    }

    private void assertForbidden(
            Runnable action
    ) {
        assertThatThrownBy(
                action::run
        )
                .isInstanceOf(
                        ForbiddenOperationException.class
                )
                .hasMessage(
                        "Аудит доступен только ADMIN или SUPER_ADMIN"
                );
    }

    private AuditEventFilter emptyFilter() {
        return new AuditEventFilter(
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private SafeAiUserPrincipal userPrincipal() {
        return SafeAiUserPrincipal
                .accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_USER"
                                )
                        )
                );
    }
}
