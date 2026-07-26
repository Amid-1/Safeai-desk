package ru.safeai.gateway.organization.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.safeai.gateway.organization.event
        .OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheService;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private UserStatusCacheService userStatusCacheService;

    @Test
    void paginatesLargeOrganizationWithoutLoadingAllIdsAtOnce() {
        List<UUID> firstBatch = IntStream
                .range(0, PAGE_SIZE)
                .mapToObj(index -> UUID.randomUUID())
                .toList();

        List<UUID> secondBatch =
                List.of(UUID.randomUUID());

        Page<UUID> firstPage = new PageImpl<>(
                firstBatch,
                PageRequest.of(0, PAGE_SIZE),
                PAGE_SIZE + 1L
        );

        Page<UUID> secondPage = new PageImpl<>(
                secondBatch,
                PageRequest.of(1, PAGE_SIZE),
                PAGE_SIZE + 1L
        );

        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID),
                any(Pageable.class)
        ))
                .thenReturn(firstPage)
                .thenReturn(secondPage);

        OrganizationStatusCacheInvalidationListener listener =
                new OrganizationStatusCacheInvalidationListener(
                        userRepository,
                        userStatusCacheService
                );

        listener.onOrganizationSecurityStateChanged(
                new OrganizationSecurityStateChangedEvent(
                        ORGANIZATION_ID,
                        9L
                )
        );

        verify(userStatusCacheService)
                .evictAll(firstBatch);

        verify(userStatusCacheService)
                .evictAll(secondBatch);

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(
                userRepository,
                times(2)
        ).findIdsByOrganizationId(
                eq(ORGANIZATION_ID),
                pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getAllValues())
                .extracting(Pageable::getPageNumber)
                .containsExactly(0, 1);

        assertThat(pageableCaptor.getAllValues())
                .extracting(Pageable::getPageSize)
                .containsOnly(PAGE_SIZE);
    }

    @Test
    void repositoryFailureIsBestEffortAndDoesNotEscape() {
        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID),
                any(Pageable.class)
        )).thenThrow(
                new IllegalStateException(
                        "PostgreSQL unavailable"
                )
        );

        OrganizationStatusCacheInvalidationListener listener =
                new OrganizationStatusCacheInvalidationListener(
                        userRepository,
                        userStatusCacheService
                );

        assertThatCode(() ->
                listener.onOrganizationSecurityStateChanged(
                        new OrganizationSecurityStateChangedEvent(
                                ORGANIZATION_ID,
                                10L
                        )
                )
        ).doesNotThrowAnyException();

        verifyNoInteractions(
                userStatusCacheService
        );
    }
}