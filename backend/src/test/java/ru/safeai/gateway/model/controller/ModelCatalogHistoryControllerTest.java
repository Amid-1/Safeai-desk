package ru.safeai.gateway.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.safeai.gateway.model.service.ModelCatalogService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelCatalogHistoryControllerTest {

    @Test
    void historyDelegatesNormalizedAuthorizationContextToService() {
        ModelCatalogService service = mock(ModelCatalogService.class);
        ModelCatalogController controller = new ModelCatalogController(service);
        var principal = ModelTestFixtures.adminPrincipal();

        when(service.findHistory(
                "openai:gpt-test",
                principal
        )).thenReturn(List.of());

        assertThat(controller.history(
                "openai:gpt-test",
                principal
        )).isEmpty();

        verify(service).findHistory(
                "openai:gpt-test",
                principal
        );
    }

    @Test
    void historyIsAReadEndpointUnderAdminControlPlaneBoundary()
            throws Exception {
        Method method = ModelCatalogController.class.getMethod(
                "history",
                String.class,
                ru.safeai.gateway.common.security.SafeAiUserPrincipal.class
        );

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/versions");

        PreAuthorize classAuthorization =
                ModelCatalogController.class.getAnnotation(PreAuthorize.class);

        assertThat(classAuthorization.value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");

        Parameter modelKey = method.getParameters()[0];
        assertThat(modelKey.getAnnotation(RequestParam.class))
                .isNotNull();
        assertThat(modelKey.getAnnotation(RequestParam.class).value())
                .isEqualTo("modelKey");
    }
}
