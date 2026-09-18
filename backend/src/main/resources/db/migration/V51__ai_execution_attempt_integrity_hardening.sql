/*
 * V51 — harden immutable physical AI execution evidence.
 *
 * V50 is immutable. This migration closes two database-level gaps without
 * rewriting existing execution facts:
 *
 * 1. a STARTED attempt can never be deleted;
 * 2. its provider/deployment/request identity cannot change while the row is
 *    transitioned to a terminal outcome.
 */

do $$
begin
    if to_regclass('public.model_execution_plans') is null
       or to_regclass('public.model_execution_attempts') is null then
        raise exception
            'Cannot apply V51: V50 AI execution tables are missing';
    end if;
end
$$;

alter table public.model_execution_attempts
    add constraint chk_model_execution_attempt_identity_text_v51
        check (
            provider_type = btrim(provider_type)
            and length(provider_type) between 1 and 32
            and provider_type !~ '[[:cntrl:]]'
            and provider_configuration_ref = btrim(provider_configuration_ref)
            and length(provider_configuration_ref) between 1 and 255
            and provider_configuration_ref !~ '[[:cntrl:]]'
            and provider_configuration_version = btrim(provider_configuration_version)
            and length(provider_configuration_version) between 1 and 64
            and provider_configuration_version !~ '[[:cntrl:]]'
            and deployment_ref = btrim(deployment_ref)
            and length(deployment_ref) between 1 and 255
            and deployment_ref !~ '[[:cntrl:]]'
            and deployment_version = btrim(deployment_version)
            and length(deployment_version) between 1 and 64
            and deployment_version !~ '[[:cntrl:]]'
            and requested_physical_model = btrim(requested_physical_model)
            and length(requested_physical_model) between 1 and 100
            and requested_physical_model !~ '[[:cntrl:]]'
            and (
                resolved_physical_model is null
                or (
                    resolved_physical_model = btrim(resolved_physical_model)
                    and length(resolved_physical_model) between 1 and 100
                    and resolved_physical_model !~ '[[:cntrl:]]'
                )
            )
            and (
                provider_request_id is null
                or (
                    provider_request_id = btrim(provider_request_id)
                    and length(provider_request_id) between 1 and 255
                    and provider_request_id !~ '[[:cntrl:]]'
                )
            )
            and (
                provider_message_id is null
                or (
                    provider_message_id = btrim(provider_message_id)
                    and length(provider_message_id) between 1 and 255
                    and provider_message_id !~ '[[:cntrl:]]'
                )
            )
            and (
                failure_type is null
                or (
                    failure_type = btrim(failure_type)
                    and length(failure_type) between 1 and 64
                    and failure_type !~ '[[:cntrl:]]'
                )
            )
        ) not valid;

alter table public.model_execution_attempts
    validate constraint chk_model_execution_attempt_identity_text_v51;

alter table public.model_execution_attempts
    add constraint chk_model_execution_attempt_terminal_failure_v51
        check (
            outcome not in ('FAILED', 'AMBIGUOUS')
            or failure_type is not null
        ) not valid;

alter table public.model_execution_attempts
    validate constraint chk_model_execution_attempt_terminal_failure_v51;

create or replace function public.enforce_model_execution_attempt_immutability_v50()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' then
        raise exception
            'V51 execution attempts cannot be deleted';
    end if;

    if old.outcome <> 'STARTED' then
        raise exception
            'V51 terminal execution attempts are immutable';
    end if;

    if new.outcome = 'STARTED' then
        raise exception
            'V51 execution attempt cannot return to STARTED';
    end if;

    if new.id is distinct from old.id
       or new.execution_plan_id is distinct from old.execution_plan_id
       or new.attempt_number is distinct from old.attempt_number
       or new.provider_attempt_id is distinct from old.provider_attempt_id
       or new.provider_type is distinct from old.provider_type
       or new.provider_configuration_ref is distinct from old.provider_configuration_ref
       or new.provider_configuration_version is distinct from old.provider_configuration_version
       or new.deployment_ref is distinct from old.deployment_ref
       or new.deployment_version is distinct from old.deployment_version
       or new.requested_physical_model is distinct from old.requested_physical_model
       or new.started_at is distinct from old.started_at
       or new.created_at is distinct from old.created_at then
        raise exception
            'V51 execution attempt identity is immutable';
    end if;

    return new;
end
$$;

comment on constraint chk_model_execution_attempt_identity_text_v51
    on public.model_execution_attempts is
    'Provider/deployment/model evidence is bounded, trimmed and free of control characters.';

comment on constraint chk_model_execution_attempt_terminal_failure_v51
    on public.model_execution_attempts is
    'FAILED and AMBIGUOUS physical attempts always retain a bounded failure classification.';
