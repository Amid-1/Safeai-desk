/* Safeai-desk/backend/src/main/resources/db/migration/V35__chat_turn_composite_integrity.sql */
-- Composite tenant-safe foreign keys and validation of deferred quota checks.
-- V33 creates the referenced unique indexes concurrently before this migration.

alter table chat_turns
    add constraint fk_chat_turns_session_tenant_user
        foreign key (session_id, organization_id, user_id)
        references chat_sessions (id, organization_id, user_id)
        deferrable initially immediate;

alter table chat_turns
    add constraint fk_chat_turns_user_message_identity
        foreign key (
            user_message_id,
            session_id,
            organization_id,
            client_request_id
        )
        references chat_messages (
            id,
            session_id,
            organization_id,
            client_request_id
        )
        deferrable initially deferred;

alter table chat_turns
    add constraint fk_chat_turns_assistant_message_identity
        foreign key (
            assistant_message_id,
            session_id,
            organization_id,
            user_message_id
        )
        references chat_messages (
            id,
            session_id,
            organization_id,
            reply_to_message_id
        )
        deferrable initially deferred;

alter table chat_quota_reservations
    add constraint fk_chat_quota_turn_tenant_user
        foreign key (turn_id, organization_id, user_id)
        references chat_turns (id, organization_id, user_id)
        deferrable initially deferred;

alter table chat_messages
    add constraint fk_chat_messages_reply_same_session_tenant
        foreign key (
            reply_to_message_id,
            session_id,
            organization_id
        )
        references chat_messages (
            id,
            session_id,
            organization_id
        )
        deferrable initially deferred
        not valid;

alter table organization_ai_quotas
    validate constraint chk_org_ai_quota_limits_non_negative;

alter table user_ai_quotas
    validate constraint chk_user_ai_quota_limits_non_negative;
