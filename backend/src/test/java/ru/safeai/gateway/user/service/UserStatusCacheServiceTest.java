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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserStatusCacheServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final String KEY =
            "user-status:" + USER_ID;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void getStatus_whenCacheHit_shouldReturnCachedStatusAndNotCallDatabase() {
        UserStatusCacheService service = enabledService();

        when(valueOperations.get(KEY))
                .thenReturn("true:true:5");

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).contains(new UserSecurityStatus(true, true, 5L));

        verifyNoInteractions(userRepository);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getStatus_whenCacheMiss_shouldLoadFromDatabaseAndCacheResult() {
        UserStatusCacheService service = enabledService();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(userEntity(true, true, 3L)));

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).contains(new UserSecurityStatus(true, true, 3L));

        verify(userRepository).findByIdWithOrganization(USER_ID);
        verify(valueOperations).set(
                KEY,
                "true:true:3",
                Duration.ofSeconds(60)
        );
    }

    @Test
    void getStatus_whenRedisFails_shouldFallbackToDatabase() {
        UserStatusCacheService service = enabledService();

        when(valueOperations.get(KEY))
                .thenThrow(new RuntimeException("Redis unavailable"));

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(userEntity(false, true, 9L)));

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).contains(new UserSecurityStatus(false, true, 9L));

        verify(userRepository).findByIdWithOrganization(USER_ID);
    }

    @Test
    void getStatus_whenUserNotFound_shouldReturnEmpty() {
        UserStatusCacheService service = enabledService();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.empty());

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).isEmpty();

        verify(userRepository).findByIdWithOrganization(USER_ID);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getStatus_whenOrganizationDisabled_shouldReturnStatusWithDisabledOrganization() {
        UserStatusCacheService service = enabledService();

        when(valueOperations.get(KEY))
                .thenReturn(null);

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(userEntity(true, false, 7L)));

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).contains(new UserSecurityStatus(true, false, 7L));

        verify(userRepository).findByIdWithOrganization(USER_ID);
        verify(valueOperations).set(
                KEY,
                "true:false:7",
                Duration.ofSeconds(60)
        );
    }

    @Test
    void evict_shouldDeleteRedisKey() {
        UserStatusCacheService service = new UserStatusCacheService(
                userRepository,
                redisTemplate,
                new UserStatusCacheProperties(
                        true,
                        Duration.ofSeconds(60)
                )
        );

        service.evict(USER_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void getStatus_whenCacheDisabled_shouldUseDatabaseOnly() {
        UserStatusCacheService service = new UserStatusCacheService(
                userRepository,
                redisTemplate,
                new UserStatusCacheProperties(
                        false,
                        Duration.ofSeconds(60)
                )
        );

        when(userRepository.findByIdWithOrganization(USER_ID))
                .thenReturn(Optional.of(userEntity(true, true, 1L)));

        Optional<UserSecurityStatus> status = service.getStatus(USER_ID);

        assertThat(status).contains(new UserSecurityStatus(true, true, 1L));

        verify(userRepository).findByIdWithOrganization(USER_ID);
        verifyNoInteractions(redisTemplate);
    }

    private UserStatusCacheService enabledService() {
        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        return new UserStatusCacheService(
                userRepository,
                redisTemplate,
                new UserStatusCacheProperties(
                        true,
                        Duration.ofSeconds(60)
                )
        );
    }

    private UserEntity userEntity(
            boolean userEnabled,
            boolean organizationEnabled,
            long tokenVersion
    ) {
        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(ORGANIZATION_ID);
        organization.setName("SafeAI");
        organization.setEnabled(organizationEnabled);

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrganization(organization);
        user.setEnabled(userEnabled);
        user.setTokenVersion(tokenVersion);

        return user;
    }
}