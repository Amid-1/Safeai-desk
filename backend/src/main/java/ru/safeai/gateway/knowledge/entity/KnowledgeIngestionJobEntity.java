package ru.safeai.gateway.knowledge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import ru.safeai.gateway.knowledge.model.KnowledgeIngestionStatus;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "knowledge_ingestion_jobs")
public class KnowledgeIngestionJobEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "knowledge_base_id", nullable = false, updatable = false)
    private UUID knowledgeBaseId;

    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "document_version_id", nullable = false, updatable = false)
    private UUID documentVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KnowledgeIngestionStatus status;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 2_000)
    private String errorMessage;

    @Generated(event = EventType.INSERT)
    @Column(
            name = "next_attempt_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant nextAttemptAt;

    @Column(name = "extractor_version", length = 128)
    private String extractorVersion;

    @Column(name = "chunker_version", length = 128)
    private String chunkerVersion;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "extracted_char_count")
    private Integer extractedCharCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (status == null) {
            status = KnowledgeIngestionStatus.PENDING;
        }
    }
}
