/*backend/src/main/resources/db/migration/V15__add_chat_message_constraints.sql*/
alter table chat_messages
    add constraint chk_chat_messages_role
        check (role in ('USER', 'ASSISTANT', 'SYSTEM'));

alter table chat_messages
    add constraint chk_chat_messages_input_tokens
        check (input_tokens is null or input_tokens >= 0);

alter table chat_messages
    add constraint chk_chat_messages_output_tokens
        check (output_tokens is null or output_tokens >= 0);

alter table chat_messages
    add constraint chk_chat_messages_cost_usd
        check (cost_usd is null or cost_usd >= 0);