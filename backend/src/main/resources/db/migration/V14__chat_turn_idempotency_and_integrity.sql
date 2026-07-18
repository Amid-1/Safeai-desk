/* Safeai-desk/backend/src/main/resources/db/migration/V14__chat_turn_idempotency_and_integrity.sql */
-- V14: chat turn idempotency and reply integrity.
-- V14: chat turn idempotency and reply integrity.
alter table chat_sessions
    add column version bigint not null default 0;

alter table chat_messages
    add column client_request_id uuid,
    add column reply_to_message_id uuid;

alter table chat_messages
    add constraint uq_chat_messages_id_session
        unique (id, session_id);

create unique index ux_chat_messages_session_client_request_user
    on chat_messages (
                      session_id,
                      client_request_id
        )
    where client_request_id is not null
        and role = 'USER';

create index idx_chat_messages_reply_to
    on chat_messages (reply_to_message_id)
    where reply_to_message_id is not null;

alter table chat_messages
    add constraint fk_chat_messages_reply_to_same_session
        foreign key (
                     reply_to_message_id,
                     session_id
            )
            references chat_messages (
                                      id,
                                      session_id
                )
            deferrable initially deferred;

alter table chat_messages
    add constraint chk_chat_messages_client_request_role
        check (
            client_request_id is null
                or role = 'USER'
            );

alter table chat_messages
    add constraint chk_chat_messages_reply_to_role
        check (
            reply_to_message_id is null
                or role = 'ASSISTANT'
            );