/* Safeai-desk/backend/src/main/resources/db/migration/V4__schema_hardening.sql */

alter table organizations
    add constraint chk_organizations_name_not_blank
        check (length(trim(name)) > 0);

alter table organizations
    add constraint chk_organizations_version_non_negative
        check (version >= 0);

alter table organizations
    add constraint chk_organizations_updated_after_created
        check (updated_at >= created_at);


alter table users
    add constraint chk_users_email_not_blank
        check (length(trim(email)) > 0);

alter table users
    add constraint chk_users_password_hash_not_blank
        check (length(trim(password_hash)) > 0);

alter table users
    add constraint chk_users_full_name_not_blank
        check (full_name is null or length(trim(full_name)) > 0);

alter table users
    add constraint chk_users_token_version_non_negative
        check (token_version >= 0);

alter table users
    add constraint chk_users_version_non_negative
        check (version >= 0);

alter table users
    add constraint chk_users_updated_after_created
        check (updated_at >= created_at);


alter table chat_sessions
    add constraint chk_chat_sessions_title_not_blank
        check (title is null or length(trim(title)) > 0);

alter table chat_sessions
    add constraint chk_chat_sessions_updated_after_created
        check (updated_at >= created_at);


alter table chat_messages
    add constraint chk_chat_messages_content_not_blank
        check (length(trim(content)) > 0);

alter table chat_messages
    add constraint chk_chat_messages_model_not_blank
        check (model is null or length(trim(model)) > 0);


alter table audit_events
    add constraint chk_audit_events_event_type_not_blank
        check (length(trim(event_type)) > 0);


alter table refresh_tokens
alter column user_agent type text;

alter table refresh_tokens
    add constraint chk_refresh_tokens_token_hash_not_blank
        check (length(trim(token_hash)) > 0);

alter table refresh_tokens
    add constraint chk_refresh_tokens_expires_after_created
        check (expires_at > created_at);

alter table refresh_tokens
    add constraint chk_refresh_tokens_revoked_after_created
        check (revoked_at is null or revoked_at >= created_at);

alter table refresh_tokens
    add constraint chk_refresh_tokens_last_used_after_created
        check (last_used_at is null or last_used_at >= created_at);

alter table refresh_tokens
    add constraint chk_refresh_tokens_not_replace_self
        check (replaced_by_token_id is null or replaced_by_token_id <> id);