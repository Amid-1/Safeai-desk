/* Safeai-desk/backend/src/main/resources/db/migration/V3__denormalize_chat_organization.sql */

alter table chat_sessions
    add column organization_id uuid;

update chat_sessions cs
set organization_id = u.organization_id
from users u
where cs.user_id = u.id;

alter table chat_sessions
    alter column organization_id set not null;

alter table chat_sessions
    add constraint fk_chat_sessions_organization
        foreign key (organization_id)
            references organizations(id);

create index idx_chat_sessions_organization_id_updated_at
    on chat_sessions (organization_id, updated_at desc);

create index idx_chat_sessions_organization_user_updated_at
    on chat_sessions (organization_id, user_id, updated_at desc);


alter table chat_messages
    add column organization_id uuid;

update chat_messages cm
set organization_id = cs.organization_id
from chat_sessions cs
where cm.session_id = cs.id;

alter table chat_messages
    alter column organization_id set not null;

alter table chat_messages
    add constraint fk_chat_messages_organization
        foreign key (organization_id)
            references organizations(id);

create index idx_chat_messages_organization_usage
    on chat_messages (organization_id, role, status, model, created_at);

create index idx_chat_messages_organization_created_at
    on chat_messages (organization_id, created_at desc);
