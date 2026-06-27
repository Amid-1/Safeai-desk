/* Safeai-desk/backend/src/main/resources/db/migration/V1__init_schema.sql */
create table organizations (
                               id uuid primary key,
                               name varchar(255) not null,
                               enabled boolean not null default true,
                               created_at timestamptz not null,
                               version bigint not null default 0
);

create unique index ux_organizations_name_lower
    on organizations (lower(name));


create table roles (
                       id uuid primary key,
                       name varchar(100) not null unique
);


create table users (
                       id uuid primary key,
                       organization_id uuid not null references organizations(id),
                       email varchar(255) not null,
                       password_hash varchar(255) not null,
                       full_name varchar(255),
                       enabled boolean not null default true,
                       created_at timestamptz not null,
                       token_version bigint not null default 0,
                       version bigint not null default 0
);

create unique index ux_users_email_lower
    on users (lower(email));

create index idx_users_organization_id
    on users (organization_id);


create table user_roles (
                            user_id uuid not null references users(id) on delete cascade,
                            role_id uuid not null references roles(id),
                            primary key (user_id, role_id)
);

create index idx_user_roles_user_id
    on user_roles (user_id);

create index idx_user_roles_role_id
    on user_roles (role_id);


create table chat_sessions (
                               id uuid primary key,
                               user_id uuid not null references users(id) on delete cascade,
                               title varchar(255),
                               created_at timestamptz not null,
                               updated_at timestamptz not null
);

create index idx_chat_sessions_user_id_updated_at
    on chat_sessions (user_id, updated_at desc);


create table chat_messages (
                               id uuid primary key,
                               session_id uuid not null references chat_sessions(id) on delete cascade,
                               role varchar(50) not null,
                               content text not null,
                               model varchar(100),
                               input_tokens int,
                               output_tokens int,
                               cost_usd numeric(12, 6),
                               status varchar(50) not null default 'COMPLETED',
                               created_at timestamptz not null,

                               constraint chk_chat_messages_role
                                   check (role in ('USER', 'ASSISTANT', 'SYSTEM')),

                               constraint chk_chat_messages_status
                                   check (status in ('PENDING', 'COMPLETED', 'FAILED')),

                               constraint chk_chat_messages_input_tokens
                                   check (input_tokens is null or input_tokens >= 0),

                               constraint chk_chat_messages_output_tokens
                                   check (output_tokens is null or output_tokens >= 0),

                               constraint chk_chat_messages_cost_usd
                                   check (cost_usd is null or cost_usd >= 0)
);

create index idx_chat_messages_session_id_created_at
    on chat_messages (session_id, created_at asc, id asc);

create index idx_chat_messages_usage
    on chat_messages (role, status, model, created_at);


create table audit_events (
                              id uuid primary key,
                              user_id uuid references users(id) on delete set null,
                              organization_id uuid not null references organizations(id),
                              event_type varchar(100) not null,
                              details jsonb,
                              created_at timestamptz not null
);

create index idx_audit_events_created_at
    on audit_events (created_at desc);

create index idx_audit_events_user_id_created_at
    on audit_events (user_id, created_at desc);

create index idx_audit_events_organization_id_created_at
    on audit_events (organization_id, created_at desc);

create index idx_audit_events_event_type_created_at
    on audit_events (event_type, created_at desc);


create table refresh_tokens (
                                id uuid primary key,
                                user_id uuid not null references users(id) on delete cascade,
                                token_hash varchar(128) not null unique,
                                expires_at timestamptz not null,
                                revoked_at timestamptz,
                                created_at timestamptz not null,
                                created_by_ip varchar(100),
                                user_agent text
);

create index idx_refresh_tokens_user_id
    on refresh_tokens (user_id);

create index idx_refresh_tokens_expires_at
    on refresh_tokens (expires_at);

create index idx_refresh_tokens_active_user
    on refresh_tokens (user_id, expires_at)
    where revoked_at is null;