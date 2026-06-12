package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.organization.repository.OrganizationRepository;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.UserResponse;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.RoleRepository;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void createWhenEmailAlreadyExistsThrowsConflictException() {
        CreateUserRequest request = new CreateUserRequest(
                UUID.randomUUID(),
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Пользователь с таким email уже существует");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenOrganizationNotFoundThrowsResourceNotFoundException() {
        UUID organizationId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                organizationId,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Организация не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenRoleNotFoundThrowsResourceNotFoundException() {
        UUID organizationId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                organizationId,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(organizationId);
        organization.setName("Demo Company");

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Роль не найдена");

        verify(userRepository, never()).save(any());
    }

    @Test
    void createWhenRequestIsValidSavesUserWithEncodedPasswordAndRoles() {
        UUID organizationId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        CreateUserRequest request = new CreateUserRequest(
                organizationId,
                "admin@test.com",
                "admin123",
                "Demo Admin",
                Set.of("ADMIN")
        );

        OrganizationEntity organization = new OrganizationEntity();
        organization.setId(organizationId);
        organization.setName("Demo Company");

        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(roleId);
        adminRole.setName("ADMIN");

        when(userRepository.existsByEmail("admin@test.com")).thenReturn(false);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode("admin123")).thenReturn("encoded-password");

        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.create(request);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());

        UserEntity savedEntity = captor.getValue();

        assertThat(savedEntity.getId()).isNotNull();
        assertThat(savedEntity.getOrganization().getId()).isEqualTo(organizationId);
        assertThat(savedEntity.getEmail()).isEqualTo("admin@test.com");
        assertThat(savedEntity.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedEntity.getFullName()).isEqualTo("Demo Admin");
        assertThat(savedEntity.isEnabled()).isTrue();
        assertThat(savedEntity.getRoles()).extracting(RoleEntity::getName).containsExactly("ADMIN");

        assertThat(response.email()).isEqualTo("admin@test.com");
        assertThat(response.organizationId()).isEqualTo(organizationId);
        assertThat(response.roles()).containsExactly("ADMIN");
    }
}