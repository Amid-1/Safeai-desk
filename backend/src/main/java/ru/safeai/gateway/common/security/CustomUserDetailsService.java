package ru.safeai.gateway.common.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull UserDetails loadUserByUsername(@NonNull String email) throws UsernameNotFoundException {
        String normalizedEmail = email.trim().toLowerCase();

        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь не найден: " + normalizedEmail
                ));

        var authorities = user.getRoles()
                .stream()
                .filter(Objects::nonNull)
                .map(RoleEntity::getName)
                .filter(Objects::nonNull)
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());

        return new SafeAiUserPrincipal(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEnabled(),
                authorities
        );
    }
}