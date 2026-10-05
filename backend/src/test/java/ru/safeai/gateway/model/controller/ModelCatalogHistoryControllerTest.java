package ru.safeai.gateway.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
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

        when(service.findHistory("openai:gpt-test", null, 100, principal))
                .thenReturn(List.of());

        assertThat(
                controller.history(
                        "openai:gpt-test",
                        null,
                        100,
                        principal
                )
        ).isEmpty();

        verify(service).findHistory(
                "openai:gpt-test",
                null,
                100,
                principal
        );
    }

    @Test
    void twoArgumentJavaCallUsesTheDefaultBoundedPage() {
        ModelCatalogService service = mock(ModelCatalogService.class);
        ModelCatalogController controller = new ModelCatalogController(service);
        var principal = ModelTestFixtures.adminPrincipal();

        when(service.findHistory("openai:gpt-test", null, 100, principal))
                .thenReturn(List.of());

        assertThat(
                controller.history(
                        "openai:gpt-test",
                        principal
                )
        ).isEmpty();

        verify(service).findHistory(
                "openai:gpt-test",
                null,
                100,
                principal
        );
    }

    @Test
    void historyDelegatesExplicitPageCursorAndLimit() {
        ModelCatalogService service = mock(ModelCatalogService.class);
        ModelCatalogController controller = new ModelCatalogController(service);
        var principal = ModelTestFixtures.adminPrincipal();

        when(service.findHistory("openai:gpt-test", 9, 25, principal))
                .thenReturn(List.of());

        assertThat(
                controller.history(
                        "openai:gpt-test",
                        9,
                        25,
                        principal
                )
        ).isEmpty();

        verify(service).findHistory(
                "openai:gpt-test",
                9,
                25,
                principal
        );
    }

    @Test
    void historyIsAReadEndpointUnderAdminControlPlaneBoundary() throws Exception {
        Method method = ModelCatalogController.class.getMethod(
                "history",
                String.class,
                Integer.class,
                int.class,
                SafeAiUserPrincipal.class
        );

        assertThat(method.getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/versions");

        PreAuthorize authorization =
                ModelCatalogController.class.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");

        Parameter[] parameters = method.getParameters();

        RequestParam modelKey = parameters[0].getAnnotation(RequestParam.class);
        assertThat(modelKey).isNotNull();
        assertThat(modelKey.value()).isEqualTo("modelKey");

        RequestParam cursor = parameters[1].getAnnotation(RequestParam.class);
        assertThat(cursor).isNotNull();
        assertThat(cursor.value()).isEqualTo("beforeVersion");
        assertThat(cursor.required()).isFalse();

        RequestParam limit = parameters[2].getAnnotation(RequestParam.class);
        assertThat(limit).isNotNull();
        assertThat(limit.value()).isEqualTo("limit");
        assertThat(limit.defaultValue()).isEqualTo("100");
    }
}
