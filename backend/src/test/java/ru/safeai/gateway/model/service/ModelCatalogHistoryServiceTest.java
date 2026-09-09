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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCatalogHistoryServiceTest {

    @Mock
    private ModelCatalogRepository repository;

    @Mock
    private RuntimeModelStatusService runtimeStatusService;

    @Mock
    private AuditEventService audit;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository,
                runtimeStatusService,
                audit,
                ModelTestFixtures.CLOCK
        );
    }

    @Test
    void adminCanReadFullVersionHistoryAndModelKeyIsNormalized() {
        ModelCatalogEntry entry = ModelTestFixtures.freeEntry();

        when(repository.findVersions("openai:gpt-test"))
                .thenReturn(List.of(entry));

        var response = service.findHistory(
                " OpenAI:GPT-Test ",
                ModelTestFixtures.adminPrincipal()
        );

        assertThat(response)
                .hasSize(1);
        assertThat(response.getFirst().modelKey())
                .isEqualTo("openai:gpt-test");
        assertThat(response.getFirst().version())
                .isEqualTo(entry.version());

        verify(repository)
                .findVersions("openai:gpt-test");
    }

    @Test
    void superAdminCanReadHistory() {
        when(repository.findVersions("openai:gpt-test"))
                .thenReturn(List.of());

        assertThat(service.findHistory(
                "openai:gpt-test",
                ModelTestFixtures.superAdminPrincipal()
        )).isEmpty();

        verify(repository)
                .findVersions("openai:gpt-test");
    }


    @Test
    void invalidModelKeyIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.findHistory(
                "   ",
                ModelTestFixtures.adminPrincipal()
        )).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void userCannotReadHistory() {
        assertThatThrownBy(() -> service.findHistory(
                "openai:gpt-test",
                ModelTestFixtures.userPrincipal()
        )).isInstanceOf(ForbiddenOperationException.class);

        verifyNoInteractions(repository);
    }
}
