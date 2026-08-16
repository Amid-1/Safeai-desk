package ru.safeai.gateway.knowledge.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseEntity;
import ru.safeai.gateway.knowledge.entity.KnowledgeBaseMembershipEntity;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseMembershipRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeBaseRepository;
import ru.safeai.gateway.knowledge.repository.KnowledgeMemberDirectoryRepository;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID KB_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock KnowledgeBaseMembershipRepository membershipRepository;
    @Mock KnowledgeMemberDirectoryRepository memberDirectoryRepository;
    @Mock AuditEventService auditEventService;
    @Mock EntityManager entityManager;

    KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(
                knowledgeBaseRepository,
                membershipRepository,
                memberDirectoryRepository,
                auditEventService,
                entityManager
        );
    }

    @Test
    void findAll_adminReadsAllTenantBases() {
        when(knowledgeBaseRepository.findAllByOrganizationId(eq(ORGANIZATION_ID), any()))
                .thenReturn(new PageImpl<>(List.of(knowledgeBase(KnowledgeBaseVisibility.MEMBERS, true))));

        Page<?> result = service.findAll(admin(), 0, 50);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(knowledgeBaseRepository).findAllByOrganizationId(eq(ORGANIZATION_ID), any());
        verify(knowledgeBaseRepository, never()).findVisibleForUser(any(), any(), any(), any());
    }

    @Test
    void findAll_userUsesVisibilityAwareRepository() {
        when(knowledgeBaseRepository.findVisibleForUser(
                eq(ORGANIZATION_ID), eq(USER_ID), eq(KnowledgeBaseVisibility.ORGANIZATION), any()
        )).thenReturn(Page.empty());

        service.findAll(user(), 0, 50);

        verify(knowledgeBaseRepository).findVisibleForUser(
                eq(ORGANIZATION_ID), eq(USER_ID), eq(KnowledgeBaseVisibility.ORGANIZATION), any()
        );
    }

    @Test
    void findById_membersBaseIsHiddenFromNonMemberUser() {
        when(knowledgeBaseRepository.findByIdAndOrganizationId(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBaseVisibility.MEMBERS, true)));
        when(membershipRepository.existsByKnowledgeBaseIdAndOrganizationIdAndUserId(
                KB_ID, ORGANIZATION_ID, USER_ID
        )).thenReturn(false);

        assertThatThrownBy(() -> service.findById(KB_ID, user()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("База знаний не найдена");
    }

    @Test
    void findById_disabledBaseIsHiddenFromUserButVisibleToAdmin() {
        KnowledgeBaseEntity base = knowledgeBase(KnowledgeBaseVisibility.ORGANIZATION, false);
        when(knowledgeBaseRepository.findByIdAndOrganizationId(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(base));

        assertThatThrownBy(() -> service.findById(KB_ID, user()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(service.findById(KB_ID, admin()).id()).isEqualTo(KB_ID);
    }

    @Test
    void create_adminCreatesNormalizedBaseAndOwnerMembership() {
        OrganizationEntity organization = organization();
        UserEntity creator = userEntity(USER_ID, true);

        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization);
        when(entityManager.find(UserEntity.class, USER_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(creator);
        when(knowledgeBaseRepository.existsByOrganizationIdAndNameIgnoreCase(
                ORGANIZATION_ID, "Production Runbooks"
        )).thenReturn(false);
        when(knowledgeBaseRepository.saveAndFlush(any(KnowledgeBaseEntity.class)))
                .thenAnswer(invocation -> {
                    KnowledgeBaseEntity entity = invocation.getArgument(0);
                    entity.setId(KB_ID);
                    return entity;
                });
        when(membershipRepository.saveAndFlush(any(KnowledgeBaseMembershipEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(
                new CreateKnowledgeBaseRequest(
                        "  Production   Runbooks  ",
                        "  Регламенты\nэксплуатации  ",
                        KnowledgeBaseVisibility.MEMBERS
                ),
                admin()
        );

        assertThat(response.id()).isEqualTo(KB_ID);
        assertThat(response.name()).isEqualTo("Production Runbooks");
        assertThat(response.description()).isEqualTo("Регламенты\nэксплуатации");
        assertThat(response.enabled()).isTrue();

        ArgumentCaptor<KnowledgeBaseMembershipEntity> captor =
                ArgumentCaptor.forClass(KnowledgeBaseMembershipEntity.class);
        verify(membershipRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(captor.getValue().getKnowledgeBaseId()).isEqualTo(KB_ID);
        assertThat(captor.getValue().getAccessLevel()).isEqualTo(KnowledgeBaseAccessLevel.OWNER);

        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.KNOWLEDGE_BASE_CREATED),
                anyMap()
        );
    }

    @Test
    void create_rejectsDuplicateNameBeforePersisting() {
        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(knowledgeBaseRepository.existsByOrganizationIdAndNameIgnoreCase(ORGANIZATION_ID, "Runbooks"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateKnowledgeBaseRequest("Runbooks", null, KnowledgeBaseVisibility.ORGANIZATION),
                admin()
        )).isInstanceOf(ConflictException.class).hasMessageContaining("уже существует");

        verify(knowledgeBaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_rejectsOrdinaryUser() {
        assertThatThrownBy(() -> service.create(
                new CreateKnowledgeBaseRequest("Runbooks", null, KnowledgeBaseVisibility.ORGANIZATION),
                user()
        )).isInstanceOf(ForbiddenOperationException.class).hasMessageContaining("ADMIN");
    }

    @Test
    void update_rejectsStaleExpectedVersion() {
        KnowledgeBaseEntity base = knowledgeBase(KnowledgeBaseVisibility.ORGANIZATION, true);
        base.setVersion(7L);

        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(knowledgeBaseRepository.findForUpdate(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(base));

        assertThatThrownBy(() -> service.update(
                KB_ID,
                new UpdateKnowledgeBaseRequest(
                        "Runbooks", null, KnowledgeBaseVisibility.ORGANIZATION, true, 6L
                ),
                admin()
        )).isInstanceOf(ConflictException.class)
                .hasMessageContaining("expectedVersion=6")
                .hasMessageContaining("actualVersion=7");

        verify(knowledgeBaseRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_noChangesDoesNotWriteOrAudit() {
        KnowledgeBaseEntity base = knowledgeBase(KnowledgeBaseVisibility.ORGANIZATION, true);
        base.setDescription("Описание");
        base.setVersion(3L);

        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(knowledgeBaseRepository.findForUpdate(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(base));

        var result = service.update(
                KB_ID,
                new UpdateKnowledgeBaseRequest(
                        "Runbooks", "Описание", KnowledgeBaseVisibility.ORGANIZATION, true, 3L
                ),
                admin()
        );

        assertThat(result.version()).isEqualTo(3L);
        verify(knowledgeBaseRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditEventService);
    }

    @Test
    void addMember_rejectsDisabledUser() {
        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(entityManager.find(UserEntity.class, OTHER_USER_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(userEntity(OTHER_USER_ID, false));

        assertThatThrownBy(() -> service.addMember(
                KB_ID,
                new CreateKnowledgeBaseMemberRequest(OTHER_USER_ID, KnowledgeBaseAccessLevel.EDITOR),
                admin()
        )).isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("отключенного пользователя");

        verifyNoInteractions(membershipRepository);
    }

    @Test
    void addMember_rejectsDuplicateMembership() {
        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(entityManager.find(UserEntity.class, OTHER_USER_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(userEntity(OTHER_USER_ID, true));
        when(knowledgeBaseRepository.findForUpdate(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBaseVisibility.MEMBERS, true)));
        when(membershipRepository.existsByKnowledgeBaseIdAndOrganizationIdAndUserId(
                KB_ID, ORGANIZATION_ID, OTHER_USER_ID
        )).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(
                KB_ID,
                new CreateKnowledgeBaseMemberRequest(OTHER_USER_ID, KnowledgeBaseAccessLevel.EDITOR),
                admin()
        )).isInstanceOf(ConflictException.class).hasMessageContaining("уже состоит");
    }

    @Test
    void updateMember_rejectsStaleExpectedVersion() {
        KnowledgeBaseMembershipEntity membership = new KnowledgeBaseMembershipEntity();
        membership.setKnowledgeBaseId(KB_ID);
        membership.setOrganizationId(ORGANIZATION_ID);
        membership.setUserId(OTHER_USER_ID);
        membership.setAccessLevel(KnowledgeBaseAccessLevel.VIEWER);
        membership.setVersion(5L);

        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(knowledgeBaseRepository.findForUpdate(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBaseVisibility.MEMBERS, true)));
        when(membershipRepository.findForUpdate(KB_ID, ORGANIZATION_ID, OTHER_USER_ID))
                .thenReturn(Optional.of(membership));

        assertThatThrownBy(() -> service.updateMember(
                KB_ID,
                OTHER_USER_ID,
                new UpdateKnowledgeBaseMemberRequest(KnowledgeBaseAccessLevel.EDITOR, 4L),
                admin()
        )).isInstanceOf(ConflictException.class).hasMessageContaining("actualVersion=5");
    }

    @Test
    void removeMember_deletesAndFlushesMatchingMembership() {
        KnowledgeBaseMembershipEntity membership = new KnowledgeBaseMembershipEntity();
        membership.setKnowledgeBaseId(KB_ID);
        membership.setOrganizationId(ORGANIZATION_ID);
        membership.setUserId(OTHER_USER_ID);
        membership.setAccessLevel(KnowledgeBaseAccessLevel.VIEWER);
        membership.setVersion(2L);

        when(entityManager.find(OrganizationEntity.class, ORGANIZATION_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(organization());
        when(entityManager.find(UserEntity.class, OTHER_USER_ID, LockModeType.PESSIMISTIC_READ))
                .thenReturn(userEntity(OTHER_USER_ID, true));
        when(knowledgeBaseRepository.findForUpdate(KB_ID, ORGANIZATION_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBaseVisibility.MEMBERS, true)));
        when(membershipRepository.findForUpdate(KB_ID, ORGANIZATION_ID, OTHER_USER_ID))
                .thenReturn(Optional.of(membership));

        service.removeMember(KB_ID, OTHER_USER_ID, 2L, admin());

        verify(membershipRepository).delete(membership);
        verify(membershipRepository).flush();
        verify(auditEventService).record(
                any(SafeAiUserPrincipal.class),
                eq(ORGANIZATION_ID),
                eq(AuditEventType.KNOWLEDGE_BASE_MEMBER_REMOVED),
                anyMap()
        );
    }

    private KnowledgeBaseEntity knowledgeBase(KnowledgeBaseVisibility visibility, boolean enabled) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(KB_ID);
        entity.setOrganizationId(ORGANIZATION_ID);
        entity.setName("Runbooks");
        entity.setVisibility(visibility);
        entity.setEnabled(enabled);
        entity.setCreatedByUserId(USER_ID);
        return entity;
    }

    private OrganizationEntity organization() {
        OrganizationEntity entity = new OrganizationEntity();
        entity.setId(ORGANIZATION_ID);
        entity.setName("Demo Company");
        entity.setEnabled(true);
        return entity;
    }

    private UserEntity userEntity(UUID id, boolean enabled) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEnabled(enabled);
        user.setOrganization(organization());
        user.setEmail(
                id.equals(USER_ID)
                        ? "admin@test.com"
                        : "user@test.com"
        );
        user.setFullName("Test User");
        return user;
    }

    private SafeAiUserPrincipal admin() {
        return principal("ROLE_ADMIN");
    }

    private SafeAiUserPrincipal user() {
        return principal("ROLE_USER");
    }

    private SafeAiUserPrincipal principal(String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                0L,
                0L,
                List.of(new SimpleGrantedAuthority(role))
        );
    }
}
