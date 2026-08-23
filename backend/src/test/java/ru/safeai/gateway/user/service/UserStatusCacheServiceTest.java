package ru.safeai.gateway.user.service;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID ORGANIZATION_ID = UUID.randomUUID();
    private static final long ORGANIZATION_AUTH_VERSION = 13L;
    private static final String KEY =
            "safeai:test:user-status:" + USER_ID;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void disabledCacheReadsPostgresAndNeverTouchesRedis() {
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(
                        Optional.of(
                                user(true, true, 7L)
                        )
                );

        UserStatusCacheService service = service(false);

        assertThat(service.getStatus(USER_ID))
                .contains(status(true, true, 7L));

        verify(userRepository)
                .findByIdWithOrganization(USER_ID);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void nullUserIdReturnsEmptyWithoutDependencies() {
        UserStatusCacheService service = service(false);

        assertThat(service.getStatus(null)).isEmpty();
        verifyNoInteractions(userRepository, redisTemplate);
    }

    @Test
    void enabledCacheReturnsValidCachedStatusWithoutPostgres() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(
                ORGANIZATION_ID
                        + ":true:true:9:"
                        + ORGANIZATION_AUTH_VERSION
        );

        UserStatusCacheService service = service(true);

        assertThat(service.getStatus(USER_ID))
                .contains(status(true, true, 9L));

        verifyNoInteractions(userRepository);
        verify(valueOperations, never()).set(
                anyString(),
                anyString(),
                any(Duration.class)
        );
    }

    @Test
    void malformedCacheFallsBackToPostgresAndRewritesValue() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(valueOperations.get(KEY))
                .thenReturn("broken-value");
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(
                        Optional.of(
                                user(true, false, 11L)
                        )
                );

        UserStatusCacheService service = service(true);

        assertThat(service.getStatus(USER_ID))
                .contains(status(true, false, 11L));

        verify(valueOperations).set(
                KEY,
                ORGANIZATION_ID
                        + ":true:false:11:"
                        + ORGANIZATION_AUTH_VERSION,
                Duration.ofSeconds(60)
        );
    }

    @Test
    void legacyFourPartCacheIsRejectedAndRewritten() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(
                ORGANIZATION_ID + ":true:true:9"
        );
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(
                        Optional.of(
                                user(true, true, 9L)
                        )
                );

        UserStatusCacheService service = service(true);

        assertThat(service.getStatus(USER_ID))
                .contains(status(true, true, 9L));

        verify(valueOperations).set(
                KEY,
                ORGANIZATION_ID
                        + ":true:true:9:"
                        + ORGANIZATION_AUTH_VERSION,
                Duration.ofSeconds(60)
        );
    }

    @Test
    void invalidBooleanOrNegativeVersionCacheIsRejected() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(valueOperations.get(KEY))
                .thenReturn(
                        ORGANIZATION_ID + ":yes:true:-1:0"
                );
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.empty());

        UserStatusCacheService service = service(true);

        assertThat(service.getStatus(USER_ID)).isEmpty();
        verify(userRepository)
                .findByIdWithOrganization(USER_ID);
    }

    @Test
    void redisReadFailureFallsBackToPostgres() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
        when(valueOperations.get(KEY))
                .thenThrow(
                        new IllegalStateException(
                                "Redis unavailable"
                        )
                );
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(
                        Optional.of(
                                user(true, true, 3L)
                        )
                );

        UserStatusCacheService service = service(true);

        assertThat(service.getStatus(USER_ID))
                .contains(status(true, true, 3L));
    }

    @Test
    void postgresFailureIsNotSwallowed() {
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenThrow(
                        new IllegalStateException(
                                "PostgreSQL unavailable"
                        )
                );

        UserStatusCacheService service = service(false);

        assertThatThrownBy(() ->
                service.getStatus(USER_ID)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL unavailable");
    }

    @Test
    void redisEvictionFailureIsBestEffortAndDoesNotEscape() {
        when(redisTemplate.delete(KEY))
                .thenThrow(
                        new IllegalStateException(
                                "Redis unavailable"
                        )
                );

        UserStatusCacheService service = service(true);

        assertThatCode(() -> service.evict(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void enabledCacheEvictsAllNonNullIds() {
        UserStatusCacheService service = service(true);

        service.evictAll(
                List.of(USER_ID, OTHER_USER_ID)
        );

        verify(redisTemplate).delete(
                List.of(
                        "safeai:test:user-status:" + USER_ID,
                        "safeai:test:user-status:" + OTHER_USER_ID
                )
        );
    }

    @Test
    void disabledCacheNeverEvictsRedis() {
        UserStatusCacheService service = service(false);

        service.evict(USER_ID);
        service.evictAll(List.of(USER_ID));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void disabledUserStatusIsReadFromPostgres() {
        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(
                        Optional.of(
                                user(false, true, 12L)
                        )
                );

        UserStatusCacheService service = service(false);

        assertThat(service.getStatus(USER_ID))
                .contains(status(false, true, 12L));
    }

    private UserStatusCacheService service(
            boolean enabled
    ) {
        return new UserStatusCacheService(
                userRepository,
                redisTemplate,
                new UserStatusCacheProperties(
                        enabled,
                        Duration.ofSeconds(60),
                        "safeai:test:user-status"
                )
        );
    }

    private UserSecurityStatus status(
            boolean userEnabled,
            boolean organizationEnabled,
            long tokenVersion
    ) {
        return new UserSecurityStatus(
                ORGANIZATION_ID,
                userEnabled,
                organizationEnabled,
                tokenVersion,
                ORGANIZATION_AUTH_VERSION
        );
    }

    private UserEntity user(
            boolean userEnabled,
            boolean organizationEnabled,
            long tokenVersion
    ) {
        OrganizationEntity organization =
                new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setEnabled(organizationEnabled);
        organization.setAuthVersion(
                ORGANIZATION_AUTH_VERSION
        );

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEnabled(userEnabled);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
