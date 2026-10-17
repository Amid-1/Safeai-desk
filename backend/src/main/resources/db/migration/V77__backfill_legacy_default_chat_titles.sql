-- SafeAI-Desk/backend/src/main/resources/db/migration/V77__backfill_legacy_default_chat_titles.sql
--
-- V77: backfill legacy placeholder chat titles without weakening V59 provenance.
--
-- V59 forbids USER -> AUTO, USER -> DEFAULT, and AUTO -> DEFAULT.
-- Preserve USER provenance when replacing a historic placeholder's visible
-- text: historical evidence cannot prove that the placeholder was not chosen
-- explicitly. DEFAULT -> AUTO remains the legitimate automatic transition.
--
-- No changes to triggers, earlier Flyway migrations, tenant/user ownership,
-- message content, created_at, or archived state. No model/provider I/O.
-- Updates are scoped to the canonical placeholder and are one-time/idempotent.
-- The version counter is advanced for each actually modified row.
--
-- IMPORTANT: Existing USER placeholders WITHOUT a usable first message must
-- stay USER. Converting them to DEFAULT would violate V59 and destroy explicit
-- title provenance. Such cases require an explicit owner rename (or a new,
-- separately audited workflow), not a silent provenance downgrade.

WITH first_user_message AS (
    SELECT DISTINCT ON (m.session_id)
           m.session_id,
           btrim(regexp_replace(m.content, '[[:space:]]+', ' ', 'g')) AS content
      FROM public.chat_messages AS m
     WHERE m.role = 'USER'
       AND btrim(m.content) <> ''
     ORDER BY m.session_id, m.created_at, m.id
),
proposed AS (
    SELECT s.id,
           CASE
               WHEN char_length(f.content) <= 72 THEN f.content
               ELSE rtrim(left(f.content, 71)) || '…'
           END AS proposed_title
      FROM public.chat_sessions AS s
      JOIN first_user_message AS f ON f.session_id = s.id
     WHERE s.title = 'Новый чат'
       AND s.title_source IN ('DEFAULT', 'USER')
),
updated AS (
    UPDATE public.chat_sessions AS s
       SET title = p.proposed_title,
           title_source = CASE
               WHEN s.title_source = 'DEFAULT' THEN 'AUTO'
               ELSE 'USER'
           END,
           version = s.version + 1
      FROM proposed AS p
     WHERE s.id = p.id
       AND s.title = 'Новый чат'
       AND s.title_source IN ('DEFAULT', 'USER')
       AND btrim(p.proposed_title) <> ''
       AND (
           s.title IS DISTINCT FROM p.proposed_title
           OR s.title_source = 'DEFAULT'
       )
     RETURNING s.id
)
SELECT count(*) AS updated_chat_titles FROM updated;

-- No blanket USER -> DEFAULT update: V59 explicitly forbids it.
-- Existing DEFAULT placeholders remain eligible for first-message naming.
