package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureReviewTypes.ReviewSummary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureOverviewControllerTest {
    @Test
    void delegatesAggregateReadToDatabaseAuthoritativeService() {
        var service = mock(ErasureReviewService.class);
        var actor = mock(SafeAiUserPrincipal.class);
        var summary = new ReviewSummary(5, 2, 1, 1);
        when(service.platformSummary(actor)).thenReturn(summary);
        assertEquals(summary, new ErasureOverviewController(service).overview(actor));
        verify(service).platformSummary(actor);
    }

    @Test
    void readControllerHasPlatformRoleBoundaryAndNoApprovalFlag() {
        var type = ErasureOverviewController.class;
        assertNull(type.getAnnotation(ConditionalOnProperty.class));
        assertEquals("hasRole('SUPER_ADMIN')", type.getAnnotation(PreAuthorize.class).value());
    }
}
