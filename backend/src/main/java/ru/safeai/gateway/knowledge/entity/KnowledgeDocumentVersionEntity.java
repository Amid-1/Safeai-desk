package ru.safeai.gateway.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "knowledge_document_versions")
public class KnowledgeDocumentVersionEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(
            name = "organization_id",
            nullable = false,
            updatable = false
    )
    private UUID organizationId;

    @Column(
            name = "knowledge_base_id",
            nullable = false,
            updatable = false
    )
    private UUID knowledgeBaseId;

    @Column(
            name = "document_id",
            nullable = false,
            updatable = false
    )
    private UUID documentId;

    @Column(
            name = "version_number",
            nullable = false,
            updatable = false
    )
    private int versionNumber;

    @Column(
            name = "original_filename",
            nullable = false,
            updatable = false,
            length = 255
    )
    private String originalFilename;

    @Column(
            name = "media_type",
            nullable = false,
            updatable = false,
            length = 127
    )
    private String mediaType;

    @Column(
            name = "size_bytes",
            nullable = false,
            updatable = false
    )
    private long sizeBytes;

    @Column(
            nullable = false,
            updatable = false,
            length = 64
    )
    private String sha256;

    @Column(
            name = "storage_key",
            nullable = false,
            updatable = false,
            length = 1024
    )
    private String storageKey;

    @Column(
            name = "created_by_user_id",
            nullable = false,
            updatable = false
    )
    private UUID createdByUserId;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "created_at",
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}