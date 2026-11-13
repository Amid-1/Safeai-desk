-- Only register audit types for strictly guarded manual NEEDS_REVIEW continuation.
-- No deletion status or historical Flyway migration is changed here.
INSERT INTO public.audit_event_types(name, description) VALUES
('KNOWLEDGE_DOCUMENT_DELETION_RESUMED', 'Authorized operator requeued NEEDS_REVIEW document erasure'),
('KNOWLEDGE_BASE_DELETION_RESUMED', 'Authorized operator requeued NEEDS_REVIEW base erasure')
ON CONFLICT (name) DO NOTHING;
