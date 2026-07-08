/* Safeai-desk/backend/src/main/resources/db/migration/V5__audit_event_types.sql */

create table audit_event_types (
                                   name varchar(100) primary key,
                                   description varchar(255)
);

insert into audit_event_types (name, description)
values
    ('USER_LOGIN_SUCCESS', 'Successful user login'),
    ('USER_LOGIN_FAILED', 'Failed user login'),
    ('USER_LOGOUT', 'User logout'),

    ('SECURITY_REFRESH_REUSE_DETECTED', 'Refresh token reuse detected'),

    ('CHAT_CREATED', 'Chat session created'),
    ('CHAT_MESSAGE_SENT', 'User chat message sent'),
    ('AI_RESPONSE_RECEIVED', 'AI response received'),
    ('AI_RESPONSE_FAILED', 'AI response failed'),

    ('USER_CREATED', 'User created'),
    ('USER_ENABLED_CHANGED', 'User enabled state changed'),
    ('USER_ROLES_CHANGED', 'User roles changed'),
    ('USER_PASSWORD_RESET', 'User password reset'),

    ('ORGANIZATION_CREATED', 'Organization created'),
    ('ORGANIZATION_NAME_CHANGED', 'Organization name changed'),
    ('ORGANIZATION_ENABLED_CHANGED', 'Organization enabled state changed'),

    ('RATE_LIMIT_EXCEEDED', 'Rate limit exceeded')
on conflict (name) do nothing;

alter table audit_events
    add constraint fk_audit_events_event_type
        foreign key (event_type)
            references audit_event_types(name);