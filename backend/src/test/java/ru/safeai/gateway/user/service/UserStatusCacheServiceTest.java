package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID SECOND_USER_ID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String PREFIX =
            "safeai:test:user-status:";

    private static final String KEY =
            PREFIX + USER_ID;

    private static final Duration TTL =
            Duration.ofSeconds(60);

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserStatusCacheService service;

    @BeforeEach
    void setUp() {
        service = new UserStatusCacheService(
                userRepository,
                redisTemplate,
                properties(true)
        );
    }

    @Test
    void cacheHitReturnsCachedStatusWithoutDatabaseCall() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(
                        ORGANIZATION_ID + ":true:true:5"
                );

        Optional<UserSecurityStatus> status =
                service.getStatus(USER_ID);

        assertThat(status).contains(
                new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        5L
                )
        );

        verifyNoInteractions(userRepository);

        verify(valueOperations, never()).set(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    void cacheMissLoadsFromDatabaseAndCachesResult() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, true, 3L)
                ));

        Optional<UserSecurityStatus> status =
                service.getStatus(USER_ID);

        assertThat(status).contains(
                new UserSecurityStatus(
                        ORGANIZATION_ID,
                        true,
                        true,
                        3L
                )
        );

        verify(userRepository)
                .findByIdWithOrganization(USER_ID);

        verify(valueOperations).set(
                KEY,
                ORGANIZATION_ID + ":true:true:3",
                TTL
        );
    }

    @Test
    void invalidCachedBooleanFallsBackToDatabase() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(
                        ORGANIZATION_ID + ":yes:true:5"
                );

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, true, 5L)
                ));

        assertThat(service.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                5L
                        )
                );

        verify(userRepository)
                .findByIdWithOrganization(USER_ID);
    }

    @Test
    void negativeCachedTokenVersionFallsBackToDatabase() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(
                        ORGANIZATION_ID + ":true:true:-1"
                );

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, true, 1L)
                ));

        assertThat(service.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                1L
                        )
                );
    }

    @Test
    void redisReadFailureFallsBackToDatabase() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenThrow(
                        new RuntimeException(
                                "Redis unavailable"
                        )
                );

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(false, true, 9L)
                ));

        assertThat(service.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                false,
                                true,
                                9L
                        )
                );
    }

    @Test
    void redisWriteFailureDoesNotHideDatabaseStatus() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, true, 2L)
                ));

        doThrow(new RuntimeException("Redis unavailable"))
                .when(valueOperations)
                .set(
                        KEY,
                        ORGANIZATION_ID + ":true:true:2",
                        TTL
                );

        assertThat(service.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                2L
                        )
                );
    }

    @Test
    void disabledOrganizationIsReturnedInSecurityStatus() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, false, 7L)
                ));

        assertThat(service.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                false,
                                7L
                        )
                );

        verify(valueOperations).set(
                KEY,
                ORGANIZATION_ID + ":true:false:7",
                TTL
        );
    }

    @Test
    void userNotFoundReturnsEmptyAndDoesNotCache() {
        stubValueOperations();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.empty());

        assertThat(service.getStatus(USER_ID))
                .isEmpty();

        verify(valueOperations, never()).set(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    void evictDeletesSingleKey() {
        service.evict(USER_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void evictAllDeletesKeysInSingleRedisCall() {
        service.evictAll(List.of(
                USER_ID,
                SECOND_USER_ID
        ));

        verify(redisTemplate).delete(List.of(
                PREFIX + USER_ID,
                PREFIX + SECOND_USER_ID
        ));
    }

    @Test
    void evictAllIgnoresNullIdentifiers() {
        service.evictAll(Arrays.asList(
                USER_ID,
                null,
                SECOND_USER_ID
        ));

        verify(redisTemplate).delete(List.of(
                PREFIX + USER_ID,
                PREFIX + SECOND_USER_ID
        ));
    }

    @Test
    void cacheDisabledUsesDatabaseOnly() {
        UserStatusCacheService disabledService =
                new UserStatusCacheService(
                        userRepository,
                        redisTemplate,
                        properties(false)
                );

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(
                        userEntity(true, true, 1L)
                ));

        assertThat(disabledService.getStatus(USER_ID))
                .contains(
                        new UserSecurityStatus(
                                ORGANIZATION_ID,
                                true,
                                true,
                                1L
                        )
                );

        verify(userRepository)
                .findByIdWithOrganization(USER_ID);

        verifyNoInteractions(redisTemplate);
    }

    private void stubValueOperations() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
    }

    private UserStatusCacheProperties properties(
            boolean enabled
    ) {
        return new UserStatusCacheProperties(
                enabled,
                TTL,
                "safeai:test:user-status"
        );
    }

    private UserEntity userEntity(
            boolean userEnabled,
            boolean organizationEnabled,
            long tokenVersion
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();

        organization.setId(ORGANIZATION_ID);
        organization.setName("SafeAI");
        organization.setEnabled(
                organizationEnabled
        );

        UserEntity user =
                new UserEntity();

        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEnabled(userEnabled);
        user.setTokenVersion(tokenVersion);

        return user;
    }
}
