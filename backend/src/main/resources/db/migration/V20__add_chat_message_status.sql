/*backend/src/main/resources/db/migration/V20__add_chat_message_status.sql*/
alter table chat_messages
    add column if not exists status varchar(50) not null default 'COMPLETED';

alter table chat_messages
    add constraint chk_chat_messages_status
        check (status in ('PENDING', 'COMPLETED', 'FAILED'));