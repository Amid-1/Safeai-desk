-- SafeAI-Desk/backend/src/test/resources/db/migration/V0__test_chat_erasure_roles.sql
-- Testcontainers fixture only. NEVER deploy these passwords or grant superuser.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_app') THEN
        CREATE ROLE safeai_app LOGIN PASSWORD 'test-only-app-password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_chat_erasure_worker') THEN
        CREATE ROLE safeai_chat_erasure_worker LOGIN PASSWORD 'test-only-worker-password'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_chat_erasure_operator') THEN
        CREATE ROLE safeai_chat_erasure_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END $$;
GRANT USAGE ON SCHEMA public TO safeai_app;
GRANT USAGE ON SCHEMA public TO safeai_chat_erasure_worker;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_chat_operator_test') THEN
        CREATE ROLE safeai_chat_operator_test LOGIN PASSWORD 'test-only-operator-password'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END $$;
GRANT safeai_chat_erasure_operator TO safeai_chat_operator_test;
GRANT USAGE ON SCHEMA public TO safeai_chat_operator_test;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='safeai_chat_operator_test2') THEN
        CREATE ROLE safeai_chat_operator_test2 LOGIN PASSWORD 'test-only-operator2-password'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
    END IF;
END $$;
GRANT safeai_chat_erasure_operator TO safeai_chat_operator_test2;
GRANT USAGE ON SCHEMA public TO safeai_chat_operator_test2;

-- V89 isolated Knowledge erasure fixtures. Never copy passwords to production.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_erasure_worker') THEN
      CREATE ROLE safeai_knowledge_erasure_worker LOGIN PASSWORD 'test-only-knowledge-worker-password'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_erasure_operator') THEN
      CREATE ROLE safeai_knowledge_erasure_operator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
        NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_operator_test_a') THEN
      CREATE ROLE safeai_knowledge_operator_test_a LOGIN PASSWORD 'test-only-knowledge-operator-a'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='safeai_knowledge_operator_test_b') THEN
      CREATE ROLE safeai_knowledge_operator_test_b LOGIN PASSWORD 'test-only-knowledge-operator-b'
        NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;
GRANT safeai_knowledge_erasure_operator TO safeai_knowledge_operator_test_a;
GRANT safeai_knowledge_erasure_operator TO safeai_knowledge_operator_test_b;
GRANT USAGE ON SCHEMA public TO safeai_knowledge_erasure_worker,
    safeai_knowledge_operator_test_a,safeai_knowledge_operator_test_b;

-- Always grant on the database connected by this *test-only* migration.
-- Different Testcontainers fixtures use different database names, including
-- 'test' and 'safeai_test'.  Never guess a database name or broaden to ALL.
DO $$
BEGIN
    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I TO '
        || 'safeai_app, safeai_chat_erasure_worker, '
        || 'safeai_chat_operator_test, safeai_chat_operator_test2, '
        || 'safeai_knowledge_erasure_worker, '
        || 'safeai_knowledge_operator_test_a, safeai_knowledge_operator_test_b',
        current_database()
    );
END $$;
