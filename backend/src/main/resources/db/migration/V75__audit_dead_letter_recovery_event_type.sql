-- SafeAI-Desk/backend/src/main/resources/db/migration/V75__audit_dead_letter_recovery_event_type.sql
-- V75: register event type for the operator-controlled Audit dead-letter recovery.
-- V73 = Organization Unicode migration; V74 = Usage hardening migration.
-- IMPORTANT: do not change applied Flyway V20/V23/V68 or reuse V73/V74.
-- This reference data alone does not create a recovery endpoint or bypass
-- V68's terminal dead-letter UPDATE restrictions.
-- A compliant recovery implementation must preserve the original event ID
-- when inserting immutable evidence, remove the terminal outbox row and
-- commit a SEPARATE required recovery audit event atomically.

INSERT INTO public.audit_event_types (name, description)
VALUES (
    'AUDIT_OUTBOX_DEAD_LETTER_RECOVERED',
    'SUPER_ADMIN recovered terminal outbox evidence; original event ID preserved'
)
ON CONFLICT (name) DO NOTHING;
