/* Safeai-desk/backend/src/main/resources/db/migration/V33__chat_turn_indexes.sql */
-- Non-transactional production indexes for chat reads, recovery and quotas.

create index concurrently if not exists idx_chat_turns_session_created
    on chat_turns (session_id, created_at desc, id desc);

create index concurrently if not exists idx_chat_turns_succeeded_history
    on chat_turns (session_id, created_at desc, id desc)
    include (user_message_id, assistant_message_id)
    where state = 'SUCCEEDED';

create index concurrently if not exists idx_chat_turns_expired_processing
    on chat_turns (lease_until, id)
    where state = 'PROCESSING';

create index concurrently if not exists idx_chat_turns_org_state_created
    on chat_turns (organization_id, state, created_at desc, id desc);

create index concurrently if not exists idx_chat_turns_user_state_created
    on chat_turns (user_id, state, created_at desc, id desc);

create index concurrently if not exists idx_chat_messages_visible_session
    on chat_messages (session_id, created_at desc, id desc)
    where role in ('USER', 'ASSISTANT');

create index concurrently if not exists idx_chat_quota_org_period
    on chat_quota_reservations (
        organization_id,
        period_start,
        state
    );

create index concurrently if not exists idx_chat_quota_user_period
    on chat_quota_reservations (
        user_id,
        period_start,
        state
    );

create index concurrently if not exists idx_chat_turns_state_completed_org
    on chat_turns (state, completed_at, organization_id)
    where state in ('FAILED', 'AMBIGUOUS');

create index concurrently if not exists idx_chat_turns_processing_lease_started
    on chat_turns (lease_until, provider_call_started_at, id)
    where state = 'PROCESSING';

-- Referenced unique indexes for composite tenant-safe foreign keys added in V35.
create unique index concurrently if not exists ux_chat_sessions_id_org_user
    on chat_sessions (id, organization_id, user_id);

create unique index concurrently if not exists ux_chat_messages_id_session_org
    on chat_messages (id, session_id, organization_id);

create unique index concurrently if not exists ux_chat_messages_user_turn_identity
    on chat_messages (
        id,
        session_id,
        organization_id,
        client_request_id
    );

create unique index concurrently if not exists ux_chat_messages_assistant_turn_identity
    on chat_messages (
        id,
        session_id,
        organization_id,
        reply_to_message_id
    );

create unique index concurrently if not exists ux_chat_turns_id_org_user
    on chat_turns (id, organization_id, user_id);
