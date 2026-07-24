/* Safeai-desk/backend/src/main/resources/db/migration/V21__refresh_token_cleanup_batch_index.sql */
-- Must run outside a transaction.
-- Requires spring.flyway.postgresql.transactional-lock=false.

create index concurrently if not exists idx_refresh_tokens_cleanup_batch
    on public.refresh_tokens (family_expires_at, id);

-- Remove indexes from earlier draft migrations if they were created manually.
drop index concurrently if exists public.idx_refresh_tokens_family_expiry;
drop index concurrently if exists public.idx_refresh_tokens_family_id;
