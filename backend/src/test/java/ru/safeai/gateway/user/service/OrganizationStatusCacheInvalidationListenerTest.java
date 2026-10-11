package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import ru.safeai.gateway.organization.event.OrganizationSecurityStateChangedEvent;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.service.UserStatusCacheProperties;
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
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final int PAGE_SIZE = 1_000;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStatusCacheService userStatusCacheService;

    @Mock
    private UserStatusCacheProperties userStatusCacheProperties;

    @Test
    void paginatesLargeOrganizationWithStableIdSort() {
        when(userStatusCacheProperties.isEnabled()).thenReturn(true);

        List<UUID> firstBatch = IntStream.range(0, PAGE_SIZE)
                .mapToObj(index -> UUID.randomUUID())
                .toList();
        List<UUID> secondBatch = List.of(UUID.randomUUID());

        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new SliceImpl<>(
                        firstBatch, invocation.getArgument(1), true))
                .thenAnswer(invocation -> new SliceImpl<>(
                        secondBatch, invocation.getArgument(1), false));

        listener().onOrganizationSecurityStateChanged(event(9L));

        verify(userStatusCacheService).evictAll(firstBatch);
        verify(userStatusCacheService).evictAll(secondBatch);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository, times(2)).findIdsByOrganizationId(
                eq(ORGANIZATION_ID), captor.capture());

        List<Pageable> pages = captor.getAllValues();
        assertThat(pages)
                .extracting(Pageable::getPageNumber)
                .containsExactly(0, 1);
        assertThat(pages)
                .extracting(Pageable::getPageSize)
                .containsOnly(PAGE_SIZE);
        assertThat(pages).allSatisfy(pageable -> {
            Sort.Order order = pageable.getSort().getOrderFor("id");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        });
    }

    @Test
    void emptyOrganizationDoesNotCallCache() {
        when(userStatusCacheProperties.isEnabled()).thenReturn(true);
        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new SliceImpl<>(
                        List.<UUID>of(), invocation.getArgument(1), false));

        listener().onOrganizationSecurityStateChanged(event(10L));

        verify(userRepository).findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class));
        verifyNoInteractions(userStatusCacheService);
    }

    @Test
    void repositoryFailureIsBestEffortAndDoesNotEscape() {
        when(userStatusCacheProperties.isEnabled()).thenReturn(true);
        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class)))
                .thenThrow(new IllegalStateException("PostgreSQL unavailable"));

        assertThatCode(() -> listener().onOrganizationSecurityStateChanged(event(11L)))
                .doesNotThrowAnyException();

        verify(userRepository).findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class));
        verifyNoInteractions(userStatusCacheService);
    }

    @Test
    void disabledCacheSkipsOrganizationScanAndEviction() {
        when(userStatusCacheProperties.isEnabled()).thenReturn(false);

        listener().onOrganizationSecurityStateChanged(event(12L));

        verify(userStatusCacheProperties).isEnabled();
        verifyNoInteractions(userRepository, userStatusCacheService);
    }

    @Test
    void cacheEvictionFailureIsBestEffortAndStopsFurtherPaging() {
        when(userStatusCacheProperties.isEnabled()).thenReturn(true);
        List<UUID> firstBatch = List.of(UUID.randomUUID());
        when(userRepository.findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class)))
                .thenAnswer(invocation -> new SliceImpl<>(
                        firstBatch, invocation.getArgument(1), true));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(userStatusCacheService).evictAll(firstBatch);

        assertThatCode(() -> listener().onOrganizationSecurityStateChanged(event(13L)))
                .doesNotThrowAnyException();

        verify(userStatusCacheService).evictAll(firstBatch);
        verify(userRepository, times(1)).findIdsByOrganizationId(
                eq(ORGANIZATION_ID), any(Pageable.class));
    }

    private static OrganizationSecurityStateChangedEvent event(long authVersion) {
        return new OrganizationSecurityStateChangedEvent(ORGANIZATION_ID, authVersion);
    }

    private OrganizationStatusCacheInvalidationListener listener() {
        return new OrganizationStatusCacheInvalidationListener(
                userRepository,
                userStatusCacheService,
                userStatusCacheProperties
        );
    }
}
