/* Safeai-desk/backend/src/main/resources/db/migration/V1__init_schema.sql */
create table organizations (
    id uuid primary key,
    name varchar(255) not null,
    created_at timestamp not null
);

create table users (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    email varchar(255) not null unique,
    password_hash varchar(255) not null,
    full_name varchar(255),
    enabled boolean not null default true,
    created_at timestamp not null
);

create table roles (
    id uuid primary key,
    name varchar(100) not null unique
);

create table user_roles (
    user_id uuid not null references users(id),
    role_id uuid not null references roles(id),
    primary key (user_id, role_id)
);

create table chat_sessions (
    id uuid primary key,
    user_id uuid not null references users(id),
    title varchar(255),
    created_at timestamp not null
);

create table chat_messages (
    id uuid primary key,
    session_id uuid not null references chat_sessions(id),
    role varchar(50) not null,
    content text not null,
    model varchar(100),
    input_tokens int,
    output_tokens int,
    cost_usd numeric(12, 6),
    created_at timestamp not null
);

create table audit_events (
    id uuid primary key,
    user_id uuid references users(id),
    event_type varchar(100) not null,
    details jsonb,
    created_at timestamp not null
);