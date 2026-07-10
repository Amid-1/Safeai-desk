/* Safeai-desk/backend/src/main/resources/db/migration/V12__enforce_tenant_and_refresh_token_integrity.sql */

-- ---------------------------------------------------------------------------
-- 1. Validate existing tenant relationships before adding composite FKs.
-- These blocks provide clearer errors if inconsistent data already exists.
-- ---------------------------------------------------------------------------

do $$
    begin
        if exists (
            select 1
            from chat_sessions cs
                     join users u on u.id = cs.user_id
            where cs.organization_id <> u.organization_id
        ) then
            raise exception
                'Cannot apply V12: chat_sessions contains rows whose organization_id does not match the owning user';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from chat_messages cm
                     join chat_sessions cs on cs.id = cm.session_id
            where cm.organization_id <> cs.organization_id
        ) then
            raise exception
                'Cannot apply V12: chat_messages contains rows whose organization_id does not match the parent chat session';
        end if;
    end
$$;

do $$
    begin
        if exists (
            select 1
            from usage_daily_user_model_rollups r
                     join users u on u.id = r.user_id
            where r.organization_id <> u.organization_id
        ) then
            raise exception
                'Cannot apply V12: usage_daily_user_model_rollups contains rows whose organization_id does not match the user';
        end if;
    end
$$;


-- ---------------------------------------------------------------------------
-- 2. Tenant-safe relationships.
-- ---------------------------------------------------------------------------

-- Allows composite foreign keys to reference a user together with its tenant.
alter table users
    add constraint uq_users_id_organization
        unique (id, organization_id);


-- A chat session must belong to the same organization as its owner.
alter table chat_sessions
    add constraint fk_chat_sessions_user_organization
        foreign key (user_id, organization_id)
            references users (id, organization_id);


-- Allows chat messages to reference a session together with its tenant.
alter table chat_sessions
    add constraint uq_chat_sessions_id_organization
        unique (id, organization_id);


-- A chat message must belong to the same organization as its parent session.
alter table chat_messages
    add constraint fk_chat_messages_session_organization
        foreign key (session_id, organization_id)
            references chat_sessions (id, organization_id);


-- A user usage rollup must belong to the same organization as the user.
alter table usage_daily_user_model_rollups
    add constraint fk_usage_daily_user_model_user_organization
        foreign key (user_id, organization_id)
            references users (id, organization_id);


-- ---------------------------------------------------------------------------
-- 3. Refresh-token rotation integrity.
-- ---------------------------------------------------------------------------

-- One rotated token can replace only one predecessor.
create unique index ux_refresh_tokens_replaced_by_token_id
    on refresh_tokens (replaced_by_token_id)
    where replaced_by_token_id is not null;


-- A token that points to its replacement must already be revoked.
alter table refresh_tokens
    add constraint chk_refresh_tokens_replacement_requires_revocation
        check (
            replaced_by_token_id is null
                or revoked_at is not null
            );

