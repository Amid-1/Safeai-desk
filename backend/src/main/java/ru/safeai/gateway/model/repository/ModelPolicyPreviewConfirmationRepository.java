package ru.safeai.gateway.model.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Только краткоживущие подтверждения, без исходных токенов и текстов запросов. */
@Repository
public class ModelPolicyPreviewConfirmationRepository {
    public record Confirmation(String draftHash, String contextHash, boolean lockout,
                               Instant expiresAt, UUID resultPolicyId) {}

    private final JdbcTemplate jdbc;

    public ModelPolicyPreviewConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID org, UUID actor, String tokenHash, String draftHash,
                      String contextHash, boolean lockout, Instant expiresAt) {
        jdbc.update("""
                insert into public.model_policy_preview_confirmations
                    (organization_id, actor_id, token_hash, draft_hash, context_hash, lockout, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (organization_id, actor_id) do update set
                    token_hash = excluded.token_hash, draft_hash = excluded.draft_hash,
                    context_hash = excluded.context_hash, lockout = excluded.lockout,
                    expires_at = excluded.expires_at, result_policy_id = null
                """, org, actor, tokenHash, draftHash, contextHash, lockout, Timestamp.from(expiresAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Confirmation> lock(UUID org, UUID actor, String tokenHash) {
        return jdbc.query("""
                select draft_hash, context_hash, lockout, expires_at, result_policy_id
                from public.model_policy_preview_confirmations
                where organization_id = ? and actor_id = ? and token_hash = ? for update
                """, (rs, row) -> new Confirmation(rs.getString("draft_hash"), rs.getString("context_hash"),
                rs.getBoolean("lockout"), rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("result_policy_id", UUID.class)), org, actor, tokenHash).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(UUID org, UUID actor, String tokenHash, UUID policyId, Instant now) {
        return jdbc.update("""
                update public.model_policy_preview_confirmations set result_policy_id = ?
                where organization_id = ? and actor_id = ? and token_hash = ?
                  and result_policy_id is null and expires_at > ?
                """, policyId, org, actor, tokenHash, Timestamp.from(now)) == 1;
    }

    /** Ограниченная очистка без ожидания строк, занятых сохранением. */
    public void purgeExpired(Instant now) {
        jdbc.update("""
                delete from public.model_policy_preview_confirmations p
                using (select organization_id, actor_id from public.model_policy_preview_confirmations
                       where expires_at <= ? order by expires_at limit 100
                       for update skip locked) expired
                where p.organization_id = expired.organization_id and p.actor_id = expired.actor_id
                """, Timestamp.from(now));
    }
}
