package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ModelCatalogHistoryServiceTest {

    private static final int DEFAULT_LIMIT =
            100;

    @Mock
    private ModelCatalogRepository repository;

    @Mock
    private RuntimeModelStatusService runtimeStatusService;

    @Mock
    private AuditEventService audit;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service =
                new ModelCatalogService(
                        repository,
                        runtimeStatusService,
                        audit,
                        ModelTestFixtures.CLOCK
                );
    }

    @Test
    void adminCanReadFullVersionHistoryAndModelKeyIsNormalized() {
        ModelCatalogEntry entry =
                ModelTestFixtures.freeEntry();

        doReturn(
                List.of(
                        entry
                )
        )
                .when(
                        repository
                )
                .findVersions(
                        "openai:gpt-test",
                        null,
                        DEFAULT_LIMIT
                );

        var response =
                service.findHistory(
                        " OpenAI:GPT-Test ",
                        null,
                        DEFAULT_LIMIT,
                        ModelTestFixtures.adminPrincipal()
                );

        assertThat(
                response
        ).hasSize(
                1
        );

        assertThat(
                response.getFirst()
                        .modelKey()
        ).isEqualTo(
                "openai:gpt-test"
        );

        assertThat(
                response.getFirst()
                        .version()
        ).isEqualTo(
                entry.version()
        );

        verify(
                repository
        ).findVersions(
                "openai:gpt-test",
                null,
                DEFAULT_LIMIT
        );
    }

    @Test
    void superAdminCanReadHistory() {
        doReturn(
                List.of()
        )
                .when(
                        repository
                )
                .findVersions(
                        "openai:gpt-test",
                        null,
                        DEFAULT_LIMIT
                );

        assertThat(
                service.findHistory(
                        "openai:gpt-test",
                        null,
                        DEFAULT_LIMIT,
                        ModelTestFixtures.superAdminPrincipal()
                )
        ).isEmpty();

        verify(
                repository
        ).findVersions(
                "openai:gpt-test",
                null,
                DEFAULT_LIMIT
        );
    }

    @Test
    void historyPaginationIsForwardedExactlyToRepository() {
        int beforeVersion =
                7;

        int limit =
                25;

        doReturn(
                List.of()
        )
                .when(
                        repository
                )
                .findVersions(
                        "openai:gpt-test",
                        beforeVersion,
                        limit
                );

        assertThat(
                service.findHistory(
                        " OpenAI:GPT-Test ",
                        beforeVersion,
                        limit,
                        ModelTestFixtures.adminPrincipal()
                )
        ).isEmpty();

        verify(
                repository
        ).findVersions(
                "openai:gpt-test",
                beforeVersion,
                limit
        );
    }

    @Test
    void invalidModelKeyIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(
                () -> service.findHistory(
                        "   ",
                        null,
                        DEFAULT_LIMIT,
                        ModelTestFixtures.adminPrincipal()
                )
        ).isInstanceOf(
                BadRequestException.class
        );

        verifyNoInteractions(
                repository
        );
    }

    @Test
    void invalidPaginationIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(
                () -> service.findHistory(
                        "openai:gpt-test",
                        0,
                        DEFAULT_LIMIT,
                        ModelTestFixtures.adminPrincipal()
                )
        ).isInstanceOf(
                BadRequestException.class
        );

        assertThatThrownBy(
                () -> service.findHistory(
                        "openai:gpt-test",
                        null,
                        0,
                        ModelTestFixtures.adminPrincipal()
                )
        ).isInstanceOf(
                BadRequestException.class
        );

        assertThatThrownBy(
                () -> service.findHistory(
                        "openai:gpt-test",
                        null,
                        201,
                        ModelTestFixtures.adminPrincipal()
                )
        ).isInstanceOf(
                BadRequestException.class
        );

        verifyNoInteractions(
                repository
        );
    }

    @Test
    void userCannotReadHistory() {
        assertThatThrownBy(
                () -> service.findHistory(
                        "openai:gpt-test",
                        null,
                        DEFAULT_LIMIT,
                        ModelTestFixtures.userPrincipal()
                )
        ).isInstanceOf(
                ForbiddenOperationException.class
        );

        verifyNoInteractions(
                repository
        );
    }
}
