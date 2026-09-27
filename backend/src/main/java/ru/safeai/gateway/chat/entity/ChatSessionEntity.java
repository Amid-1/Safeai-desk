// backend/src/main/java/ru/safeai/gateway/chat/entity/ChatSessionEntity.java
package ru.safeai.gateway.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import ru.safeai.gateway.chat.title.ChatTitlePolicy;
import ru.safeai.gateway.chat.title.ChatTitleSource;
import ru.safeai.gateway.organization.entity.OrganizationEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "chat_sessions")
public class ChatSessionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private OrganizationEntity organization;

    /**
     * Title changes must go through renameByUser/assignAutomaticTitle so the
     * provenance state cannot be accidentally bypassed.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "title_source", nullable = false, length = 16)
    private ChatTitleSource titleSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by_user_id")
    private UUID archivedByUserId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static ChatSessionEntity create(
            UserEntity user,
            String title,
            Instant now
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        ChatSessionEntity session =
                new ChatSessionEntity();

        session.id = UUID.randomUUID();
        session.user = user;
        session.organization = Objects.requireNonNull(
                user.getOrganization(),
                "user.organization не должен быть null"
        );

        boolean defaultTitle =
                title == null || title.isBlank();

        session.title =
                ChatTitlePolicy.normalizeCreationTitle(
                        title
                );

        session.titleSource = defaultTitle
                ? ChatTitleSource.DEFAULT
                : ChatTitleSource.USER;

        session.createdAt = now;
        session.updatedAt = now;

        return session;
    }

    public void touch(Instant now) {
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        if (createdAt != null
                && now.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt не может быть раньше createdAt"
            );
        }

        updatedAt = now;
    }

    /**
     * Assigns a deterministic automatic title exactly once and only while the
     * session still owns the DEFAULT title.
     *
     * @return true when the title was changed, false when USER/AUTO already
     *         owns the title.
     */
    public boolean assignAutomaticTitle(
            String automaticTitle,
            Instant now
    ) {
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        Objects.requireNonNull(
                titleSource,
                "titleSource не должен быть null"
        );

        if (titleSource != ChatTitleSource.DEFAULT) {
            return false;
        }

        String normalized =
                ChatTitlePolicy.normalizeManualTitle(
                        automaticTitle
                );

        touch(now);

        title = normalized;
        titleSource = ChatTitleSource.AUTO;

        return true;
    }

    /**
     * Explicit user intent always wins over automatic naming.
     */
    public void renameByUser(
            String newTitle,
            Instant now
    ) {
        String normalized =
                ChatTitlePolicy.normalizeManualTitle(
                        newTitle
                );

        touch(now);

        title = normalized;
        titleSource = ChatTitleSource.USER;
    }

    public void archive(
            UUID userId,
            Instant now
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );
        Objects.requireNonNull(
                now,
                "now не должен быть null"
        );

        if (archivedAt != null) {
            throw new IllegalStateException(
                    "Чат уже архивирован"
            );
        }

        touch(now);
        archivedAt = now;
        archivedByUserId = userId;
    }

    @PrePersist
    @PreUpdate
    void validateInvariant() {
        Objects.requireNonNull(
                id,
                "chat session id не должен быть null"
        );
        Objects.requireNonNull(
                user,
                "chat session user не должен быть null"
        );
        Objects.requireNonNull(
                organization,
                "chat session organization не должен быть null"
        );
        Objects.requireNonNull(
                createdAt,
                "createdAt не должен быть null"
        );
        Objects.requireNonNull(
                updatedAt,
                "updatedAt не должен быть null"
        );
        Objects.requireNonNull(
                titleSource,
                "titleSource не должен быть null"
        );

        title =
                ChatTitlePolicy.normalizeStoredTitle(
                        title
                );

        if (titleSource == ChatTitleSource.DEFAULT
                && !ChatTitlePolicy.DEFAULT_TITLE.equals(title)) {
            throw new IllegalStateException(
                    "DEFAULT titleSource требует title = \""
                            + ChatTitlePolicy.DEFAULT_TITLE
                            + "\""
            );
        }

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalStateException(
                    "chat session updatedAt не может быть раньше createdAt"
            );
        }

        if ((archivedAt == null)
                != (archivedByUserId == null)) {
            throw new IllegalStateException(
                    "archivedAt и archivedByUserId должны задаваться вместе"
            );
        }

        if (archivedAt != null
                && archivedAt.isBefore(createdAt)) {
            throw new IllegalStateException(
                    "archivedAt не может быть раньше createdAt"
            );
        }

        if (user.getOrganization() != null
                && user.getOrganization().getId() != null
                && organization.getId() != null
                && !user.getOrganization()
                .getId()
                .equals(organization.getId())) {
            throw new IllegalStateException(
                    "chat session user и organization принадлежат разным tenant"
            );
        }
    }
}
