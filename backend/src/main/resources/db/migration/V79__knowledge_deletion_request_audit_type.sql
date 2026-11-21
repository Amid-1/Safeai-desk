-- SafeAI-Desk/backend/src/main/resources/db/migration/V79__knowledge_deletion_request_audit_type.sql
-- Register the event used by the atomic document deletion request.
-- This migration does not enable DELETE nor purge any data.
INSERT INTO public.audit_event_types(name, description)
VALUES ('KNOWLEDGE_DOCUMENT_DELETION_REQUESTED',
        'A document erasure request was accepted atomically with a durable deletion intent')
ON CONFLICT (name) DO NOTHING;
