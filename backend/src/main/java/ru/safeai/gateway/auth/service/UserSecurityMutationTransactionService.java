package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Транзакционная точка security mutations.
 *
 * <p>Порядок неизменяем:</p>
 * <ol>
 *     <li>pessimistic lock UserEntity;</li>
 *     <li>изменение security state;</li>
 *     <li>увеличение tokenVersion;</li>
 *     <li>терминальный отзыв refresh sessions;</li>
 *     <li>commit.</li>
 * </ol>
 *
 * <p>UserService должен делегировать сюда фактическую mutation после
 * authorization/business checks. Cache invalidation и audit выполняются
 * после успешного возврата/через AFTER_COMMIT listener.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSecurityMutationTransactionService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Transactional
    public long resetPassword(
            UUID userId,
            String encodedPassword
    ) {
        UserEntity user = lockUser(userId);

        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException(
                    "encodedPassword не должен быть пустым"
            );
        }

        user.setPasswordHash(encodedPassword);
        long tokenVersion = incrementTokenVersion(user);

        terminateUserSessions(
                userId,
                RefreshTokenRevocationReason.PASSWORD_RESET
        );

        return tokenVersion;
    }

    @Transactional
    public long replaceRoles(
            UUID userId,
            Set<String> roleNames
    ) {
        UserEntity user = lockUser(userId);
        Set<RoleEntity> roles = resolveRoles(roleNames);

        user.setRoles(new HashSet<>(roles));
        long tokenVersion = incrementTokenVersion(user);

        terminateUserSessions(
                userId,
                RefreshTokenRevocationReason.ROLE_CHANGED
        );

        return tokenVersion;
    }

    @Transactional
    public long disableUser(UUID userId) {
        UserEntity user = lockUser(userId);

        user.setEnabled(false);
        long tokenVersion = incrementTokenVersion(user);

        terminateUserSessions(
                userId,
                RefreshTokenRevocationReason.USER_DISABLED
        );

        return tokenVersion;
    }

    private UserEntity lockUser(UUID userId) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        return userRepository.findByIdForSecurityUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Пользователь не найден: " + userId
                ));
    }

    private Set<RoleEntity> resolveRoles(Set<String> roleNames) {
        Objects.requireNonNull(
                roleNames,
                "roleNames не должны быть null"
        );

        Set<String> normalized = roleNames.stream()
                .map(roleName -> Objects.requireNonNull(
                        roleName,
                        "roleName не должен быть null"
                ))
                .map(String::trim)
                .filter(roleName -> !roleName.isBlank())
                .map(roleName -> roleName.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "У пользователя должна быть хотя бы одна роль"
            );
        }

        return normalized.stream()
                .map(roleName -> roleRepository.findByName(roleName)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Роль не найдена: " + roleName
                        )))
                .collect(Collectors.toUnmodifiableSet());
    }

    private long incrementTokenVersion(UserEntity user) {
        long nextVersion = Math.addExact(
                user.getTokenVersion(),
                1L
        );

        user.setTokenVersion(nextVersion);
        return nextVersion;
    }

    private void terminateUserSessions(
            UUID userId,
            RefreshTokenRevocationReason reason
    ) {
        int affectedRows = refreshTokenRepository
                .terminateAllByUserId(
                        userId,
                        clock.instant(),
                        reason
                );

        log.debug(
                "Security mutation terminated refresh-token rows: "
                        + "userId={}, affectedRows={}, reason={}",
                userId,
                affectedRows,
                reason
        );
    }
}
