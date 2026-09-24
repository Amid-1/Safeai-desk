package ru.safeai.gateway.knowledge.storage.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** V56: physical S3 work must never happen inside the metadata transaction. */
@Service
@SuppressWarnings("SqlResolve") // public.knowledge_storage_upload_intents is created by Flyway V56.
public class KnowledgeStorageUploadJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public KnowledgeStorageUploadJournal(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.independent = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.independent.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Commit the intent BEFORE making the S3 PUT request. */
    public UUID begin(
            UUID baseId, UUID documentId, UUID versionId,
            String key, String sha256, long byteCount, SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(user);
        UUID id = UUID.randomUUID();
        independent.executeWithoutResult(status -> jdbc.update("""
                insert into public.knowledge_storage_upload_intents
                    (id, organization_id, knowledge_base_id, document_id,
                     document_version_id, created_by_user_id, storage_key,
                     content_sha256, content_size_bytes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, user.getOrganizationId(), baseId, documentId, versionId,
                user.getId(), key, sha256, byteCount));
        return id;
    }

    /** S3 returned success; never claim success after ambiguous PUT failure. */
    public void stored(UUID id, UUID versionId) {
        independent.executeWithoutResult(status -> {
            int n = jdbc.update("""
                    update public.knowledge_storage_upload_intents
                    set state='STORED', stored_at=now()
                    where id=? and document_version_id=? and state='INTENT'
                    """, id, versionId);
            if (n != 1) throw new IllegalStateException(
                    "Knowledge upload intent was superseded before object confirmation");
        });
    }

    /** Must execute inside the SAME TX as document version and audit rows. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void linked(UUID versionId, String key, UUID organizationId) {
        int n = jdbc.update("""
                update public.knowledge_storage_upload_intents
                set state='LINKED', linked_at=now()
                where document_version_id=? and storage_key=?
                  and organization_id=? and state='STORED'
                """, versionId, key, organizationId);
        if (n != 1) throw new IllegalStateException(
                "Knowledge upload object cannot be linked: journal state changed");
    }

    /** SQL-only discovery: not all abandoned INTENT rows prove S3 PUT completed. */
    @Transactional
    public int quarantineUncertain(Instant cutoff, int maximum) {
        return jdbc.update("""
                with candidates as (
                    select id from public.knowledge_storage_upload_intents
                    where state='INTENT' and created_at < ?
                    order by created_at, id limit ? for update skip locked
                )
                update public.knowledge_storage_upload_intents intent
                set state='NEEDS_REVIEW', reviewed_at=now(),
                    last_error_code='UNCERTAIN_S3_PUT'
                from candidates where intent.id=candidates.id
                """, Timestamp.from(cutoff), maximum);
    }

    /** If a worker died after S3 delete, no blind physical retry is safe. */
    @Transactional
    public int quarantineExpiredCleanup(Instant cutoff, int maximum) {
        return jdbc.update("""
                with candidates as (
                    select id from public.knowledge_storage_upload_intents
                    where state='CLEANING' and cleanup_claimed_at < ?
                    order by cleanup_claimed_at, id
                    limit ? for update skip locked
                )
                update public.knowledge_storage_upload_intents intent
                set state='NEEDS_REVIEW', reviewed_at=now(),
                    cleanup_token=null, cleanup_claimed_at=null,
                    last_error_code='STALE_AMBIGUOUS_S3_DELETE'
                from candidates where intent.id=candidates.id
                """, Timestamp.from(cutoff), maximum);
    }

    /** A historical reconciliation can prove a STORED object is already linked. */
    @Transactional
    public int reconcileLinked(Instant cutoff, int maximum) {
        return jdbc.update("""
                with candidates as (
                    select intent.id
                    from public.knowledge_storage_upload_intents intent
                    join public.knowledge_document_versions version
                      on version.id=intent.document_version_id
                     and version.organization_id=intent.organization_id
                     and version.knowledge_base_id=intent.knowledge_base_id
                     and version.document_id=intent.document_id
                     and version.storage_key=intent.storage_key
                    where intent.state='STORED' and intent.stored_at < ?
                    order by intent.stored_at, intent.id
                    limit ? for update of intent skip locked
                )
                update public.knowledge_storage_upload_intents intent
                set state='LINKED', linked_at=now()
                from candidates where intent.id=candidates.id
                """, Timestamp.from(cutoff), maximum);
    }

    /** The row lock conflicts with linked(), preventing a late publication. */
    @Transactional
    public CleanupClaim claimStoredOrphan(Instant cutoff) {
        UUID token = UUID.randomUUID();
        List<CleanupClaim> claimed = jdbc.query("""
                with candidates as (
                    select intent.id
                    from public.knowledge_storage_upload_intents intent
                    where intent.state='STORED'
                      and intent.stored_at < ?
                      and not exists (
                          select 1 from public.knowledge_document_versions version
                          where version.id=intent.document_version_id
                             or version.storage_key=intent.storage_key
                      )
                    order by intent.stored_at, intent.id
                    limit 1 for update skip locked
                )
                update public.knowledge_storage_upload_intents intent
                set state='CLEANING', cleanup_token=?, cleanup_claimed_at=now()
                from candidates where intent.id=candidates.id
                returning intent.id, intent.storage_key, intent.cleanup_token
                """, (rs, rn) -> new CleanupClaim(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_key"),
                        rs.getObject("cleanup_token", UUID.class)),
                Timestamp.from(cutoff), token);
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    @Transactional
    public void cleaned(CleanupClaim claim) {
        int n = jdbc.update("""
                update public.knowledge_storage_upload_intents
                set state='CLEANED', cleaned_at=now(), cleanup_token=null,
                    cleanup_claimed_at=null, last_error_code=null
                where id=? and state='CLEANING' and cleanup_token=?
                """, claim.id(), claim.token());
        if (n != 1) throw new IllegalStateException("Lost orphan-cleanup ownership");
    }

    /** S3 deletion can fail ambiguously: quarantine instead of blind retry. */
    @Transactional
    public void cleanupUncertain(CleanupClaim claim) {
        int n = jdbc.update("""
                update public.knowledge_storage_upload_intents
                set state='NEEDS_REVIEW', reviewed_at=now(),
                    cleanup_token=null, cleanup_claimed_at=null,
                    last_error_code='UNCERTAIN_S3_DELETE'
                where id=? and state='CLEANING' and cleanup_token=?
                """, claim.id(), claim.token());
        if (n != 1) throw new IllegalStateException("Lost orphan-cleanup ownership");
    }

    public record CleanupClaim(UUID id, String key, UUID token) {
        public CleanupClaim {
            Objects.requireNonNull(id); Objects.requireNonNull(key); Objects.requireNonNull(token);
        }
    }
}
