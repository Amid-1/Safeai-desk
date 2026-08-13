package ru.safeai.gateway.knowledge.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.persistence.DatabaseConstraintClassifier;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseMemberResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeBaseResponse;
import ru.safeai.gateway.knowledge.dto.KnowledgeMemberCandidateResponse;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_USER = "ROLE_USER";

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CANDIDATE_LIMIT = 50;
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;
    private static final int MAX_MEMBER_QUERY_LENGTH = 255;

    private static final String KB_NAME_UNIQUE =
            "ux_knowledge_bases_org_name_lower";

    private static final String KB_MEMBER_UNIQUE =
            "uq_knowledge_base_memberships_kb_user";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseMembershipRepository membershipRepository;
    private final KnowledgeMemberDirectoryRepository memberDirectoryRepository;
    private final AuditEventService auditEventService;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<KnowledgeBaseResponse> findAll(
            SafeAiUserPrincipal currentUser,
            int page,
            int size
    ) {
        requireDataPlaneUser(currentUser);

        PageRequest pageable = knowledgePageRequest(page, size);

        if (hasAuthority(currentUser, ROLE_ADMIN)) {
            return knowledgeBaseRepository
                    .findAllByOrganizationId(
                            currentUser.getOrganizationId(),
                            pageable
                    )
                    .map(KnowledgeBaseResponse::from);
        }

        return knowledgeBaseRepository
                .findVisibleForUser(
                        currentUser.getOrganizationId(),
                        currentUser.getId(),
                        KnowledgeBaseVisibility.ORGANIZATION,
                        pageable
                )
                .map(KnowledgeBaseResponse::from);
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse findById(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId не должен быть null"
        );
        requireDataPlaneUser(currentUser);

        KnowledgeBaseEntity entity = knowledgeBaseRepository
                .findByIdAndOrganizationId(
                        knowledgeBaseId,
                        currentUser.getOrganizationId()
                )
                .orElseThrow(() -> knowledgeBaseNotFound(knowledgeBaseId));

        if (hasAuthority(currentUser, ROLE_ADMIN)) {
            return KnowledgeBaseResponse.from(entity);
        }

        if (!entity.isEnabled()) {
            throw knowledgeBaseNotFound(knowledgeBaseId);
        }

        if (entity.getVisibility() == KnowledgeBaseVisibility.MEMBERS
                && !membershipRepository
                .existsByKnowledgeBaseIdAndOrganizationIdAndUserId(
                        entity.getId(),
                        currentUser.getOrganizationId(),
                        currentUser.getId()
                )) {
            throw knowledgeBaseNotFound(knowledgeBaseId);
        }

        return KnowledgeBaseResponse.from(entity);
    }

    @Transactional
    public KnowledgeBaseResponse create(
            CreateKnowledgeBaseRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireAdmin(currentUser);

        OrganizationEntity organization =
                lockEnabledOrganization(currentUser.getOrganizationId());

        KnowledgeBaseVisibility visibility =
                requireValue(
                        request.visibility(),
                        "visibility"
                );

        String name = KnowledgeBaseNameNormalizer.normalize(request.name());
        String description = normalizeDescription(request.description());

        if (knowledgeBaseRepository
                .existsByOrganizationIdAndNameIgnoreCase(
                        organization.getId(),
                        name
                )) {
            throw duplicateKnowledgeBaseName(name);
        }

        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setOrganizationId(organization.getId());
        entity.setName(name);
        entity.setDescription(description);
        entity.setVisibility(visibility);
        entity.setEnabled(true);
        entity.setCreatedByUserId(currentUser.getId());

        KnowledgeBaseEntity saved;

        try {
            saved = saveAndRefresh(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    KB_NAME_UNIQUE
            )) {
                throw duplicateKnowledgeBaseName(name);
            }

            throw exception;
        }

        UserEntity creator = lockTenantUser(
                currentUser.getId(),
                organization.getId()
        );

        KnowledgeBaseMembershipEntity owner =
                new KnowledgeBaseMembershipEntity();

        owner.setKnowledgeBaseId(saved.getId());
        owner.setOrganizationId(organization.getId());
        owner.setUserId(creator.getId());
        owner.setAccessLevel(KnowledgeBaseAccessLevel.OWNER);

        saveMembershipAndRefresh(owner);

        auditEventService.record(
                currentUser,
                organization.getId(),
                AuditEventType.KNOWLEDGE_BASE_CREATED,
                Map.of(
                        "knowledgeBaseId", saved.getId().toString(),
                        "name", saved.getName(),
                        "visibility", saved.getVisibility().name(),
                        "enabled", saved.isEnabled(),
                        "creatorMembership",
                        KnowledgeBaseAccessLevel.OWNER.name()
                )
        );

        return KnowledgeBaseResponse.from(saved);
    }

    @Transactional
    public KnowledgeBaseResponse update(
            UUID knowledgeBaseId,
            UpdateKnowledgeBaseRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                knowledgeBaseId,
                "knowledgeBaseId не должен быть null"
        );
        Objects.requireNonNull(request, "request не должен быть null");
        requireAdmin(currentUser);

        UUID organizationId = currentUser.getOrganizationId();
        lockEnabledOrganization(organizationId);

        KnowledgeBaseEntity entity = knowledgeBaseRepository
                .findForUpdate(knowledgeBaseId, organizationId)
                .orElseThrow(() -> knowledgeBaseNotFound(knowledgeBaseId));

        requireExpectedVersion(
                request.expectedVersion(),
                entity.getVersion(),
                "База знаний"
        );

        KnowledgeBaseVisibility visibility =
                requireValue(
                        request.visibility(),
                        "visibility"
                );

        boolean enabled =
                requireValue(
                        request.enabled(),
                        "enabled"
                );

        String name = KnowledgeBaseNameNormalizer.normalize(request.name());
        String description = normalizeDescription(request.description());

        if (!entity.getName().equalsIgnoreCase(name)
                && knowledgeBaseRepository
                .existsByOrganizationIdAndNameIgnoreCaseAndIdNot(
                        organizationId,
                        name,
                        entity.getId()
                )) {
            throw duplicateKnowledgeBaseName(name);
        }

        String oldName = entity.getName();
        KnowledgeBaseVisibility oldVisibility = entity.getVisibility();
        boolean oldEnabled = entity.isEnabled();
        String oldDescription = entity.getDescription();

        boolean changed =
                !Objects.equals(oldName, name)
                        || !Objects.equals(oldDescription, description)
                        || oldVisibility != visibility
                        || oldEnabled != enabled;

        if (!changed) {
            return KnowledgeBaseResponse.from(entity);
        }

        entity.setName(name);
        entity.setDescription(description);
        entity.setVisibility(visibility);
        entity.setEnabled(enabled);

        KnowledgeBaseEntity saved;

        try {
            saved = saveAndRefresh(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    KB_NAME_UNIQUE
            )) {
                throw duplicateKnowledgeBaseName(name);
            }

            throw exception;
        }

        auditEventService.record(
                currentUser,
                organizationId,
                AuditEventType.KNOWLEDGE_BASE_UPDATED,
                Map.of(
                        "knowledgeBaseId", saved.getId().toString(),
                        "oldName", oldName,
                        "newName", saved.getName(),
                        "oldVisibility", oldVisibility.name(),
                        "newVisibility", saved.getVisibility().name(),
                        "oldEnabled", oldEnabled,
                        "newEnabled", saved.isEnabled(),
                        "descriptionChanged",
                        !Objects.equals(
                                oldDescription,
                                saved.getDescription()
                        )
                )
        );

        return KnowledgeBaseResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<KnowledgeBaseMemberResponse> findMembers(
            UUID knowledgeBaseId,
            SafeAiUserPrincipal currentUser,
            int page,
            int size
    ) {
        requireAdmin(currentUser);

        UUID organizationId = currentUser.getOrganizationId();

        assertKnowledgeBaseExists(
                knowledgeBaseId,
                organizationId
        );

        return membershipRepository.findMemberResponses(
                knowledgeBaseId,
                organizationId,
                memberPageRequest(page, size)
        );
    }

    @Transactional(readOnly = true)
    public List<KnowledgeMemberCandidateResponse> searchMemberCandidates(
            String query,
            int limit,
            SafeAiUserPrincipal currentUser
    ) {
        requireAdmin(currentUser);

        if (limit < 1 || limit > MAX_CANDIDATE_LIMIT) {
            throw new BadRequestException(
                    "limit должен быть в диапазоне 1.."
                            + MAX_CANDIDATE_LIMIT
            );
        }

        String normalized = query == null
                ? ""
                : query.strip().toLowerCase(Locale.ROOT);

        if (normalized.length() > MAX_MEMBER_QUERY_LENGTH) {
            throw new BadRequestException(
                    "Строка поиска пользователя не должна превышать "
                            + MAX_MEMBER_QUERY_LENGTH
                            + " символов"
            );
        }

        return List.copyOf(
                memberDirectoryRepository.search(
                        currentUser.getOrganizationId(),
                        normalized,
                        PageRequest.of(0, limit)
                )
        );
    }

    @Transactional
    public KnowledgeBaseMemberResponse addMember(
            UUID knowledgeBaseId,
            CreateKnowledgeBaseMemberRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireAdmin(currentUser);

        UUID organizationId = currentUser.getOrganizationId();
        lockEnabledOrganization(organizationId);

        UserEntity targetUser = lockTenantUser(
                requireValue(
                        request.userId(),
                        "userId"
                ),
                organizationId
        );

        if (!targetUser.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Нельзя добавить отключенного пользователя в базу знаний"
            );
        }

        KnowledgeBaseEntity knowledgeBase =
                knowledgeBaseRepository
                        .findForUpdate(
                                knowledgeBaseId,
                                organizationId
                        )
                        .orElseThrow(
                                () -> knowledgeBaseNotFound(
                                        knowledgeBaseId
                                )
                        );

        if (membershipRepository
                .existsByKnowledgeBaseIdAndOrganizationIdAndUserId(
                        knowledgeBase.getId(),
                        organizationId,
                        targetUser.getId()
                )) {
            throw duplicateMembership(targetUser.getId());
        }

        KnowledgeBaseAccessLevel accessLevel =
                requireValue(
                        request.accessLevel(),
                        "accessLevel"
                );

        KnowledgeBaseMembershipEntity membership =
                new KnowledgeBaseMembershipEntity();

        membership.setKnowledgeBaseId(knowledgeBase.getId());
        membership.setOrganizationId(organizationId);
        membership.setUserId(targetUser.getId());
        membership.setAccessLevel(accessLevel);

        KnowledgeBaseMembershipEntity saved;

        try {
            saved = saveMembershipAndRefresh(membership);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintClassifier.isUniqueViolation(
                    exception,
                    KB_MEMBER_UNIQUE
            )) {
                throw duplicateMembership(targetUser.getId());
            }

            throw exception;
        }

        auditEventService.record(
                currentUser,
                organizationId,
                AuditEventType.KNOWLEDGE_BASE_MEMBER_ADDED,
                Map.of(
                        "knowledgeBaseId", knowledgeBase.getId().toString(),
                        "targetUserId", targetUser.getId().toString(),
                        "targetUserEmail", targetUser.getEmail(),
                        "accessLevel", saved.getAccessLevel().name()
                )
        );

        return toMemberResponse(saved, targetUser);
    }

    @Transactional
    public KnowledgeBaseMemberResponse updateMember(
            UUID knowledgeBaseId,
            UUID userId,
            UpdateKnowledgeBaseMemberRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(request, "request не должен быть null");
        requireAdmin(currentUser);

        UUID organizationId = currentUser.getOrganizationId();
        lockEnabledOrganization(organizationId);

        knowledgeBaseRepository
                .findForUpdate(knowledgeBaseId, organizationId)
                .orElseThrow(() -> knowledgeBaseNotFound(knowledgeBaseId));

        KnowledgeBaseMembershipEntity membership =
                membershipRepository
                        .findForUpdate(
                                knowledgeBaseId,
                                organizationId,
                                userId
                        )
                        .orElseThrow(
                                () -> membershipNotFound(userId)
                        );

        requireExpectedVersion(
                request.expectedVersion(),
                membership.getVersion(),
                "Участник базы знаний"
        );

        UserEntity targetUser = lockTenantUser(
                userId,
                organizationId
        );

        KnowledgeBaseAccessLevel accessLevel =
                requireValue(
                        request.accessLevel(),
                        "accessLevel"
                );

        KnowledgeBaseAccessLevel oldLevel = membership.getAccessLevel();

        if (oldLevel == accessLevel) {
            return toMemberResponse(membership, targetUser);
        }

        membership.setAccessLevel(accessLevel);

        KnowledgeBaseMembershipEntity saved =
                saveMembershipAndRefresh(membership);

        auditEventService.record(
                currentUser,
                organizationId,
                AuditEventType.KNOWLEDGE_BASE_MEMBER_UPDATED,
                Map.of(
                        "knowledgeBaseId", knowledgeBaseId.toString(),
                        "targetUserId", userId.toString(),
                        "targetUserEmail", targetUser.getEmail(),
                        "oldAccessLevel", oldLevel.name(),
                        "newAccessLevel", saved.getAccessLevel().name()
                )
        );

        return toMemberResponse(saved, targetUser);
    }

    @Transactional
    public void removeMember(
            UUID knowledgeBaseId,
            UUID userId,
            Long expectedVersion,
            SafeAiUserPrincipal currentUser
    ) {
        requireAdmin(currentUser);

        UUID organizationId = currentUser.getOrganizationId();
        lockEnabledOrganization(organizationId);

        knowledgeBaseRepository
                .findForUpdate(knowledgeBaseId, organizationId)
                .orElseThrow(() -> knowledgeBaseNotFound(knowledgeBaseId));

        KnowledgeBaseMembershipEntity membership =
                membershipRepository
                        .findForUpdate(
                                knowledgeBaseId,
                                organizationId,
                                userId
                        )
                        .orElseThrow(
                                () -> membershipNotFound(userId)
                        );

        requireExpectedVersion(
                expectedVersion,
                membership.getVersion(),
                "Участник базы знаний"
        );

        UserEntity targetUser = lockTenantUser(
                userId,
                organizationId
        );

        KnowledgeBaseAccessLevel oldLevel = membership.getAccessLevel();

        membershipRepository.delete(membership);
        membershipRepository.flush();

        auditEventService.record(
                currentUser,
                organizationId,
                AuditEventType.KNOWLEDGE_BASE_MEMBER_REMOVED,
                Map.of(
                        "knowledgeBaseId", knowledgeBaseId.toString(),
                        "targetUserId", userId.toString(),
                        "targetUserEmail", targetUser.getEmail(),
                        "oldAccessLevel", oldLevel.name()
                )
        );
    }

    private KnowledgeBaseEntity saveAndRefresh(
            KnowledgeBaseEntity entity
    ) {
        KnowledgeBaseEntity saved =
                knowledgeBaseRepository.saveAndFlush(entity);

        entityManager.refresh(saved);
        return saved;
    }

    private KnowledgeBaseMembershipEntity saveMembershipAndRefresh(
            KnowledgeBaseMembershipEntity entity
    ) {
        KnowledgeBaseMembershipEntity saved =
                membershipRepository.saveAndFlush(entity);

        entityManager.refresh(saved);
        return saved;
    }

    private UserEntity lockTenantUser(
            UUID userId,
            UUID organizationId
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        UserEntity user = entityManager.find(
                UserEntity.class,
                userId,
                LockModeType.PESSIMISTIC_READ
        );

        if (
                user == null
                || !organizationId.equals(
                        user.getOrganization().getId()
                )
        ) {
            throw new ResourceNotFoundException(
                    "Пользователь организации не найден: "
                            + userId
            );
        }

        return user;
    }

    private OrganizationEntity lockEnabledOrganization(
            UUID organizationId
    ) {
        OrganizationEntity organization = entityManager.find(
                OrganizationEntity.class,
                organizationId,
                LockModeType.PESSIMISTIC_READ
        );

        if (organization == null) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: " + organizationId
            );
        }

        if (!organization.isEnabled()) {
            throw new ForbiddenOperationException(
                    "Организация отключена"
            );
        }

        return organization;
    }

    private void assertKnowledgeBaseExists(
            UUID knowledgeBaseId,
            UUID organizationId
    ) {
        if (knowledgeBaseRepository
                .findByIdAndOrganizationId(
                        knowledgeBaseId,
                        organizationId
                )
                .isEmpty()) {
            throw knowledgeBaseNotFound(knowledgeBaseId);
        }
    }

    private void requireAdmin(SafeAiUserPrincipal currentUser) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!hasAuthority(currentUser, ROLE_ADMIN)) {
            throw new ForbiddenOperationException(
                    "Операция доступна только ADMIN организации"
            );
        }
    }

    private void requireDataPlaneUser(
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        if (!hasAuthority(currentUser, ROLE_ADMIN)
                && !hasAuthority(currentUser, ROLE_USER)) {
            throw new ForbiddenOperationException(
                    "Базы знаний доступны только tenant ADMIN/USER"
            );
        }
    }

    private boolean hasAuthority(
            SafeAiUserPrincipal currentUser,
            String authority
    ) {
        return currentUser.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private PageRequest knowledgePageRequest(
            int page,
            int size
    ) {
        validatePage(page, size);

        return PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("updatedAt"),
                        Sort.Order.desc("id")
                )
        );
    }

    private PageRequest memberPageRequest(
            int page,
            int size
    ) {
        validatePage(page, size);

        return PageRequest.of(
                page,
                size
        );
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new BadRequestException(
                    "page не должен быть отрицательным"
            );
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException(
                    "size должен быть в диапазоне 1.."
                            + MAX_PAGE_SIZE
            );
        }
    }

    private String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();

        if (normalized.isEmpty()) {
            return null;
        }

        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BadRequestException(
                    "Описание базы знаний не должно превышать "
                            + MAX_DESCRIPTION_LENGTH
                            + " символов"
            );
        }

        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);

            if (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\t') {
                throw new BadRequestException(
                        "Описание базы знаний содержит недопустимые управляющие символы"
                );
            }

            offset += Character.charCount(codePoint);
        }

        return normalized;
    }

    private void requireExpectedVersion(
            Long expectedVersion,
            long actualVersion,
            String resourceName
    ) {
        if (expectedVersion == null) {
            throw new BadRequestException(
                    "expectedVersion должен быть указан"
            );
        }

        if (expectedVersion < 0L) {
            throw new BadRequestException(
                    "expectedVersion должен быть неотрицательным числом"
            );
        }

        long expected = expectedVersion;

        if (expected != actualVersion) {
            throw new ConflictException(
                    resourceName
                            + " уже изменён другим запросом: expectedVersion="
                            + expectedVersion
                            + ", actualVersion="
                            + actualVersion
            );
        }
    }

    private KnowledgeBaseMemberResponse toMemberResponse(
            KnowledgeBaseMembershipEntity membership,
            UserEntity user
    ) {
        return new KnowledgeBaseMemberResponse(
                membership.getKnowledgeBaseId(),
                membership.getUserId(),
                user.getEmail(),
                user.getFullName(),
                membership.getAccessLevel(),
                membership.getVersion(),
                membership.getCreatedAt(),
                membership.getUpdatedAt()
        );
    }

    private <T> T requireValue(
            T value,
            String fieldName
    ) {
        if (value == null) {
            throw new BadRequestException(
                    fieldName + " должен быть указан"
            );
        }

        return value;
    }

    private ConflictException duplicateKnowledgeBaseName(
            String name
    ) {
        return new ConflictException(
                "База знаний с таким названием уже существует: " + name
        );
    }

    private ConflictException duplicateMembership(UUID userId) {
        return new ConflictException(
                "Пользователь уже состоит в этой базе знаний: " + userId
        );
    }

    private ResourceNotFoundException knowledgeBaseNotFound(
            UUID knowledgeBaseId
    ) {
        return new ResourceNotFoundException(
                "База знаний не найдена: " + knowledgeBaseId
        );
    }

    private ResourceNotFoundException membershipNotFound(
            UUID userId
    ) {
        return new ResourceNotFoundException(
                "Участник базы знаний не найден: " + userId
        );
    }
}
