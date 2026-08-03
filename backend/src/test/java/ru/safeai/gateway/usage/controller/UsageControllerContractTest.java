package ru.safeai.gateway.usage.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.PagedResponse;
import ru.safeai.gateway.usage.dto.UsageDateModelFilter;
import ru.safeai.gateway.usage.dto.UsagePageRequest;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.service.UsageQueryService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageControllerContractTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant FROM =
            Instant.parse(
                    "2026-06-01T00:00:00Z"
            );

    private static final Instant TO =
            Instant.parse(
                    "2026-07-01T00:00:00Z"
            );

    @Mock
    private UsageQueryService service;

    private UsageController controller;

    @BeforeEach
    void setUp() {
        controller =
                new UsageController(service);
    }

    @Test
    void summaryUsesValidatedPageDtoInsteadOfRawClientPageable() {
        SafeAiUserPrincipal principal =
                principal();

        when(
                service.getUsageSummary(
                        eq(FROM),
                        eq(TO),
                        eq("gpt-5"),
                        any(Pageable.class),
                        eq(principal)
                )
        ).thenReturn(
                new SliceImpl<>(
                        List.of(),
                        PageRequest.of(
                                0,
                                50
                        ),
                        false
                )
        );

        PagedResponse<UsageSummaryResponse> response =
                controller.summary(
                        new UsageDateModelFilter(
                                FROM,
                                TO,
                                "  gpt-5  "
                        ),
                        new UsagePageRequest(
                                null,
                                null
                        ),
                        principal
                );

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(service).getUsageSummary(
                eq(FROM),
                eq(TO),
                eq("gpt-5"),
                pageable.capture(),
                eq(principal)
        );

        assertThat(
                pageable.getValue()
                        .getPageNumber()
        ).isZero();

        assertThat(
                pageable.getValue()
                        .getPageSize()
        ).isEqualTo(50);

        assertThat(
                pageable.getValue()
                        .getSort()
                        .isUnsorted()
        ).isTrue();

        assertThat(response.content())
                .isEmpty();
    }

    @Test
    void explicitPageAndSizeAreForwardedWithoutClientSort() {
        SafeAiUserPrincipal principal =
                principal();

        when(
                service.getUsageSummary(
                        eq(FROM),
                        eq(TO),
                        isNull(),
                        any(Pageable.class),
                        eq(principal)
                )
        ).thenReturn(
                new SliceImpl<>(
                        List.of(),
                        PageRequest.of(
                                2,
                                100
                        ),
                        false
                )
        );

        controller.summary(
                new UsageDateModelFilter(
                        FROM,
                        TO,
                        " "
                ),
                new UsagePageRequest(
                        2,
                        100
                ),
                principal
        );

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(service).getUsageSummary(
                eq(FROM),
                eq(TO),
                isNull(),
                pageable.capture(),
                eq(principal)
        );

        assertThat(
                pageable.getValue()
                        .getPageNumber()
        ).isEqualTo(2);

        assertThat(
                pageable.getValue()
                        .getPageSize()
        ).isEqualTo(100);

        assertThat(
                pageable.getValue()
                        .getSort()
                        .isUnsorted()
        ).isTrue();
    }

    private SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                0L,
                List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_ADMIN"
                        )
                )
        );
    }
}