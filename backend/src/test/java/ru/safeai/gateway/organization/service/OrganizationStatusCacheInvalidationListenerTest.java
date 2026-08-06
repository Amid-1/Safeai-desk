package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationStatusCacheInvalidationListenerTest {

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final int PAGE_SIZE = 1_000;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStatusCacheService
            userStatusCacheService;

    @Test
    void paginatesLargeOrganizationWithStableIdSort() {
        List<UUID> firstBatch =
                IntStream.range(
                                0,
                                PAGE_SIZE
                        )
                        .mapToObj(index ->
                                UUID.randomUUID()
                        )
                        .toList();

        List<UUID> secondBatch =
                List.of(
                        UUID.randomUUID()
                );

        when(
                userRepository
                        .findIdsByOrganizationId(
                                eq(ORGANIZATION_ID),
                                any(Pageable.class)
                        )
        )
                .thenAnswer(invocation ->
                        new SliceImpl<>(
                                firstBatch,
                                invocation.getArgument(1),
                                true
                        )
                )
                .thenAnswer(invocation ->
                        new SliceImpl<>(
                                secondBatch,
                                invocation.getArgument(1),
                                false
                        )
                );

        listener()
                .onOrganizationSecurityStateChanged(
                        new OrganizationSecurityStateChangedEvent(
                                ORGANIZATION_ID,
                                9L
                        )
                );

        verify(
                userStatusCacheService
        ).evictAll(firstBatch);

        verify(
                userStatusCacheService
        ).evictAll(secondBatch);

        ArgumentCaptor<Pageable> captor =
                ArgumentCaptor.forClass(
                        Pageable.class
                );

        verify(
                userRepository,
                times(2)
        ).findIdsByOrganizationId(
                eq(ORGANIZATION_ID),
                captor.capture()
        );

        assertThat(
                captor.getAllValues()
        )
                .extracting(
                        Pageable::getPageNumber
                )
                .containsExactly(
                        0,
                        1
                );

        assertThat(
                captor.getAllValues()
        )
                .extracting(
                        Pageable::getPageSize
                )
                .containsOnly(
                        PAGE_SIZE
                );

        assertThat(
                captor.getAllValues()
        ).allSatisfy(pageable ->
                assertThat(
                        pageable.getSort()
                                .getOrderFor("id")
                ).isNotNull()
        );
    }

    @Test
    void emptyOrganizationDoesNotCallCache() {
        when(
                userRepository
                        .findIdsByOrganizationId(
                                eq(ORGANIZATION_ID),
                                any(Pageable.class)
                        )
        ).thenAnswer(invocation ->
                new SliceImpl<>(
                        List.of(),
                        invocation.getArgument(1),
                        false
                )
        );

        listener()
                .onOrganizationSecurityStateChanged(
                        new OrganizationSecurityStateChangedEvent(
                                ORGANIZATION_ID,
                                10L
                        )
                );

        verify(
                userStatusCacheService,
                never()
        ).evictAll(any());
    }

    @Test
    void repositoryFailureIsBestEffortAndDoesNotEscape() {
        when(
                userRepository
                        .findIdsByOrganizationId(
                                eq(ORGANIZATION_ID),
                                any(Pageable.class)
                        )
        ).thenThrow(
                new IllegalStateException(
                        "PostgreSQL unavailable"
                )
        );

        assertThatCode(() ->
                listener()
                        .onOrganizationSecurityStateChanged(
                                new OrganizationSecurityStateChangedEvent(
                                        ORGANIZATION_ID,
                                        11L
                                )
                        )
        ).doesNotThrowAnyException();

        verifyNoInteractions(
                userStatusCacheService
        );
    }

    private OrganizationStatusCacheInvalidationListener
    listener() {
        return new OrganizationStatusCacheInvalidationListener(
                userRepository,
                userStatusCacheService
        );
    }
}
