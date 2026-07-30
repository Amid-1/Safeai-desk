/* Safeai-desk/backend/src/main/resources/db/migration/V27__audit_query_and_outbox_indexes.sql */
-- flyway:executeInTransaction=false
/*
 * Concurrent production indexes.
 *
 * Keep:
 * spring.flyway.postgresql.transactional-lock=false
 */


create index concurrently if not exists
    idx_audit_events_created_id_desc
on public.audit_events (
    created_at desc,
    id desc
);

create index concurrently if not exists
    idx_audit_events_type_created_id_desc
on public.audit_events (
    event_type,
    created_at desc,
    id desc
);

create index concurrently if not exists
    idx_audit_events_actor_user_created_id_desc
on public.audit_events (
    actor_user_id,
    created_at desc,
    id desc
)
where actor_user_id is not null;

create index concurrently if not exists
    idx_audit_events_actor_email_pattern_created_id
on public.audit_events (
    actor_email text_pattern_ops,
    created_at desc,
    id desc
)
where actor_email is not null;

create index concurrently if not exists
    idx_audit_events_org_created_id_desc
on public.audit_events (
    organization_id,
    created_at desc,
    id desc
);

create index concurrently if not exists
    idx_audit_events_org_type_created_id_desc
on public.audit_events (
    organization_id,
    event_type,
    created_at desc,
    id desc
);

create index concurrently if not exists
    idx_audit_events_org_actor_user_created_id_desc
on public.audit_events (
    organization_id,
    actor_user_id,
    created_at desc,
    id desc
)
where actor_user_id is not null;

create index concurrently if not exists
    idx_audit_events_org_actor_email_pattern_created_id
on public.audit_events (
    organization_id,
    actor_email text_pattern_ops,
    created_at desc,
    id desc
)
where actor_email is not null;

create index concurrently if not exists
    idx_audit_outbox_ready
on public.audit_outbox (
    next_attempt_at,
    created_at,
    id
)
where dead_lettered_at is null;

create index concurrently if not exists
    idx_audit_outbox_dead_letter
on public.audit_outbox (
    dead_lettered_at desc,
    id
)
where dead_lettered_at is not null;

