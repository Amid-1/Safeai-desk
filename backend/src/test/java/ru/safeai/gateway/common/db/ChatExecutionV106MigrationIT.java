// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/common/db/ChatExecutionV106MigrationIT.java
package ru.safeai.gateway.common.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL 16 + actual production migration/ACL entry point; disposable databases only.
 */
@Tag("integration")
class ChatExecutionV106MigrationIT {
    private static final String PASSWORD = "v106-test-only-password";
    private static final String DB = "safeai_v106_disposable";
    private static final String MIGRATOR = "safeai_migrator";
    private static final String APP = "safeai_app";
    private static final String WORKER = "safeai_chat_erasure_worker";
    private static final String KNOWLEDGE_WORKER = "safeai_knowledge_erasure_worker";
    private static final String OPERATOR_A = "v106_operator_a";
    private static final String OPERATOR_B = "v106_operator_b";
    private static final String PROOF_KEY = "11".repeat(32);
    private static final UUID ORG = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID LEGAL_A = UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID LEGAL_B = UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID CHAT = UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final UUID MESSAGE = UUID.fromString("71000000-0000-0000-0000-000000000006");
    private static final UUID REQUEST = UUID.fromString("71000000-0000-0000-0000-000000000007");
    private static final UUID PREVIEW = UUID.fromString("71000000-0000-0000-0000-000000000008");
    private static final UUID HOLD = UUID.fromString("71000000-0000-0000-0000-000000000009");
    private static final UUID REVIEW_HOLD = UUID.fromString("71000000-0000-0000-0000-000000000010");

    @Test
    void cleanInstallRunsActualMigrationMainAndEnforcesV106AndV107Guards() throws Exception {
        exercise(0);
    }

    @Test
    void upgradeFromV105PreservesChatAndRequestEvidenceAndEnforcesAllGuards() throws Exception {
        exercise(105);
    }

    @Test
    void upgradeFromV106PreservesExistingEpochsAndInstallsV107() throws Exception {
        exercise(106);
    }

    @Test
    void upgradeSealedV106RetainsRevokedCandidateAndAllReservedIdentifiers() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrateToHistoricalVersion(url, "106");
            UUID permit = UUID.randomUUID(), nonce = UUID.randomUUID();
            try (var c = connect(url, MIGRATOR)) {
                seed(c);
                execute(c, "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
                execute(c, record(permit, nonce, 0));
                execute(c, "UPDATE public.erasure_execution_permits_v106 SET state='REVOKED' WHERE permit_id=" + lit(permit));
            }
            migrate(url);
            try (var c = connect(url, MIGRATOR)) {
                assertEquals(108L, number(c, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
                assertEquals("SEALED", text(c, "SELECT mode FROM public.chat_execution_cutover_v106"));
                assertEquals("REVOKED", text(c, "SELECT state FROM public.erasure_execution_permits_v106 WHERE permit_id=" + lit(permit)));
                assertEquals(8L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106 WHERE permit_id=" + lit(permit)));
                denied(c, "23505", record(UUID.randomUUID(), nonce, 0));
                assertEquals(0L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
                assertEquals("V106 test content", text(c, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
            }
        }
    }

    @Test
    void v105HoldBlocksRealV86WorkerAndResumeButAllowsTwoPersonCancellation() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrate(url);
            try (var c = connect(url, MIGRATOR); var app = connect(url, APP); var worker = connect(url, WORKER);
                 var opA = connect(url, OPERATOR_A); var opB = connect(url, OPERATOR_B)) {
                seed(c);
                execute(c, "INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret) VALUES (1,decode('" + PROOF_KEY + "','hex'))");
                execute(c, "INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled) VALUES(" + lit(ORG) + "," + lit(LEGAL_A) + ",'LEGAL',true)");
                app.setAutoCommit(false);
                try {
                    execute(app, legacyIntent());
                    execute(app, "UPDATE public.chat_sessions SET erasure_state='ERASING',version=version+1 WHERE id=" + lit(CHAT));
                    app.commit();
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                UUID op = UUID.fromString(text(c, "SELECT id::text FROM public.chat_erasure_operations_v86 WHERE chat_id=" + lit(CHAT)));
                UUID token = UUID.randomUUID();
                execute(worker, "UPDATE public.chat_erasure_operations_v86 SET lease_token=" + lit(token) + ",lease_until=clock_timestamp()+interval '2 minutes',attempts=attempts+1 WHERE id=" + lit(op));
                app.setAutoCommit(false);
                try {
                    actorProof(app, LEGAL_A, "HOLD_APPLIED", 1, "v107-apply-hold");
                    execute(app, "INSERT INTO public.erasure_review_holds_v105(id,request_id,organization_id,reason_code,applied_by_user_id,apply_key) VALUES(" + lit(REVIEW_HOLD) + "," + lit(REQUEST) + "," + lit(ORG) + ",'LEGAL_REQUEST'," + lit(LEGAL_A) + ",'v107-apply-hold')");
                    app.commit();
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                execute(worker, "SELECT set_config('safeai.chat.erasure_operation','" + op + "',false),set_config('safeai.chat.erasure_token','" + token + "',false)");
                assertTrue(flag(worker, "SELECT public.chat_erasure_authorized_v86(" + lit(CHAT) + ")"), "Positive control: genuine live V86 lease");
                SQLException failure = expectSqlFailure(worker, "UPDATE public.chat_messages SET content='[СОДЕРЖИМОЕ УДАЛЕНО]' WHERE id=" + lit(MESSAGE));
                assertEquals("23514", failure.getSQLState());
                assertTrue(failure.getMessage().contains("V107_COMBINED_LEGAL_HOLD"), failure.getMessage());
                assertEquals("V106 test content", text(c, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
                execute(worker, "UPDATE public.chat_erasure_operations_v86 SET state='NEEDS_REVIEW',last_error_code='SQLSTATE_23514',lease_token=NULL,lease_until=NULL WHERE id=" + lit(op));
                for (var operator : List.of(opA, opB))
                    execute(operator, "SELECT public.chat_operator_approve_v86('RESUME'," + lit(op) + ",'V107-RESUME-TEST')");
                denied(opA, "23514", "SELECT public.chat_operator_resolve_erasure_v86(" + lit(op) + ",'RESUME','V107-RESUME-TEST')");
                assertEquals("NEEDS_REVIEW", text(c, "SELECT state FROM public.chat_erasure_operations_v86 WHERE id=" + lit(op)));
                for (var operator : List.of(opA, opB))
                    execute(operator, "SELECT public.chat_operator_approve_v86('CANCEL'," + lit(op) + ",'V107-CANCEL-TEST')");
                execute(opA, "SELECT public.chat_operator_resolve_erasure_v86(" + lit(op) + ",'CANCEL','V107-CANCEL-TEST')");
                assertEquals("CANCELLED", text(c, "SELECT state FROM public.chat_erasure_operations_v86 WHERE id=" + lit(op)));
                assertEquals("AVAILABLE", text(c, "SELECT erasure_state FROM public.chat_sessions WHERE id=" + lit(CHAT)));
                assertEquals("V106 test content", text(c, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
                assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_review_holds_v105 WHERE id=" + lit(REVIEW_HOLD) + " AND released_at IS NULL"));
            }
        }
    }

    @Test
    void upgradeFromV107RevokesHistoricalInvokerGrantsWithoutChangingEvidence() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrateToHistoricalVersion(url, "107");
            long authority;
            long holds;
            String catalogue;
            UUID permit = UUID.randomUUID(), nonce = UUID.randomUUID();
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP)) {
                seed(owner);
                authority = epoch(owner, "authority_epoch");
                holds = epoch(owner, "all_holds_epoch");
                assertEquals(438264452L, number(owner, "SELECT checksum FROM public.flyway_schema_history WHERE version='107' AND success"));
                catalogue = text(owner, "SELECT encode(sha256(convert_to(string_agg(object_kind||':'||object_name||':'||digest,'|' ORDER BY object_kind,object_name),'UTF8')),'hex') FROM public.erasure_gate_catalog_v107");
                // Real historical V107: preserve a committed cutover event and a recorded candidate.
                execute(owner, "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
                execute(owner, record(permit, nonce, 0));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CUTOVER_SEALED'"));
                assertEquals(8L, number(owner, "SELECT count(*) FROM public.erasure_execution_identifiers_v106 WHERE permit_id=" + lit(permit)));
                // Reproduce the historical generic invoker EXECUTE grants that
                // could be present after the V107 runtime ACL bootstrap.
                execute(owner, """
                    GRANT EXECUTE ON FUNCTION public.guard_erasure_review_v105(), public.guard_erasure_hold_v105()
                    TO safeai_app, safeai_chat_erasure_worker, safeai_knowledge_erasure_worker
                    """);
                assertTrue(flag(app, "SELECT has_function_privilege(current_user,'public.guard_erasure_hold_v105()','EXECUTE')"));
                assertEquals(107L, number(owner, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
            }
            migrate(url);
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP);
                 var worker = connect(url, WORKER); var knowledge = connect(url, KNOWLEDGE_WORKER);
                 var opA = connect(url, OPERATOR_A); var opB = connect(url, OPERATOR_B)) {
                verifyV108Private(owner, app, worker, knowledge, opA, opB);
                assertEquals(108L, number(owner, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
                assertEquals(authority, epoch(owner, "authority_epoch"));
                assertEquals(holds, epoch(owner, "all_holds_epoch"));
                assertEquals(438264452L, number(owner, "SELECT checksum FROM public.flyway_schema_history WHERE version='107' AND success"));
                assertEquals(catalogue, text(owner, "SELECT encode(sha256(convert_to(string_agg(object_kind||':'||object_name||':'||digest,'|' ORDER BY object_kind,object_name),'UTF8')),'hex') FROM public.erasure_gate_catalog_v107"));
                assertEquals("SEALED", text(owner, "SELECT mode FROM public.chat_execution_cutover_v106"));
                assertEquals("RECORDED_ONLY", text(owner, "SELECT state FROM public.erasure_execution_permits_v106 WHERE permit_id=" + lit(permit)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
                execute(owner, "UPDATE public.erasure_execution_permits_v106 SET state='REVOKED' WHERE permit_id=" + lit(permit));
                assertEquals("REVOKED", text(owner, "SELECT state FROM public.erasure_execution_permits_v106 WHERE permit_id=" + lit(permit)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CANDIDATE_REVOKED' AND subject_id=" + lit(permit)));
                assertEquals(2L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
                assertEquals(8L, number(owner, "SELECT count(*) FROM public.erasure_execution_identifiers_v106 WHERE permit_id=" + lit(permit)));
                denied(owner, "23505", record(UUID.randomUUID(), nonce, 0));
                assertEquals("PENDING", text(owner, "SELECT review_state FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_review_events_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals("V106 test content", text(owner, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
            }
            migrate(url);
        }
    }

    @Test
    void privateV105GuardsPreserveIndependentAuthorityAndProofAfterV108() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrate(url);
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP);
                 var worker = connect(url, WORKER); var knowledge = connect(url, KNOWLEDGE_WORKER);
                 var opA = connect(url, OPERATOR_A); var opB = connect(url, OPERATOR_B)) {
                seed(owner);
                verifyV108Private(owner, app, worker, knowledge, opA, opB);
                execute(owner, "INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret) VALUES (1,decode('" + PROOF_KEY + "','hex'))");
                assertEquals(0L, number(owner, "SELECT review_version FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                // Even a valid HMAC cannot substitute for an independent REVIEW grant.
                rejectReviewDecision(app, LEGAL_A, true, "23503");
                for (UUID actor : List.of(OWNER, LEGAL_A)) {
                    execute(owner, "INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled) VALUES(" + lit(ORG) + "," + lit(actor) + ",'REVIEW',true)");
                }
                rejectReviewDecision(app, OWNER, true, "23503");
                rejectReviewDecision(app, LEGAL_A, false, "42501");
                // A revocation that wins the authority row lock must invalidate the decision.
                try (var observer = connect(url, MIGRATOR); var pool = Executors.newSingleThreadExecutor()) {
                    long pid = number(app, "SELECT pg_backend_pid()");
                    owner.setAutoCommit(false);
                    try {
                        execute(owner, "UPDATE public.erasure_review_authorities_v105 SET enabled=false WHERE organization_id=" + lit(ORG) + " AND user_id=" + lit(LEGAL_A) + " AND authority='REVIEW'");
                        var attempt = pool.submit(() -> {
                            rejectReviewDecision(app, LEGAL_A, true, "23503");
                            return true;
                        });
                        awaitLock(observer, pid);
                        owner.commit();
                        assertTrue(attempt.get(10, TimeUnit.SECONDS));
                    } finally {
                        owner.rollback();
                        owner.setAutoCommit(true);
                        pool.shutdownNow();
                        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
                    }
                }
                assertEquals("PENDING", text(owner, "SELECT review_state FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(0L, number(owner, "SELECT review_version FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_review_events_v105 WHERE request_id=" + lit(REQUEST)));
                execute(owner, "UPDATE public.erasure_review_authorities_v105 SET enabled=true WHERE organization_id=" + lit(ORG) + " AND user_id=" + lit(LEGAL_A) + " AND authority='REVIEW'");
                app.setAutoCommit(false);
                try {
                    actorProof(app, LEGAL_A, "APPROVED_REVIEW_ONLY", 1, "v108-review-approval");
                    execute(app, reviewDecision(LEGAL_A));
                    app.commit();
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                assertEquals("APPROVED_REVIEW_ONLY", text(owner, "SELECT review_state FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT review_version FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(2L, number(owner, "SELECT count(*) FROM public.erasure_review_events_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.chat_erasure_operations_v86"));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
                assertEquals("V106 test content", text(owner, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
            }
            // Real migration main must keep the definer delta and ACL strict on reapplication.
            migrate(url);
        }
    }

    private static String reviewDecision(UUID actor) {
        return "UPDATE public.erasure_reviews_v105 SET review_state='APPROVED_REVIEW_ONLY',"
            + "review_version=review_version+1,updated_by_user_id=" + lit(actor)
            + ",last_event_code='APPROVED_REVIEW_ONLY',last_key='v108-review-approval',"
            + "reason_code='VERIFIED_OWNER_REQUEST',updated_at=transaction_timestamp()"
            + " WHERE request_id=" + lit(REQUEST) + " AND organization_id=" + lit(ORG);
    }

    private static void rejectReviewDecision(Connection app, UUID actor, boolean validProof, String sqlState) throws Exception {
        app.setAutoCommit(false);
        try {
            if (validProof) {
                actorProof(app, actor, "APPROVED_REVIEW_ONLY", 1, "v108-review-approval");
            } else {
                execute(app, "SELECT set_config('safeai.erasure.review.actor_proof_v105','',true)");
            }
            denied(app, sqlState, reviewDecision(actor));
        } finally {
            app.rollback();
            app.setAutoCommit(true);
        }
    }

    private static void verifyV108Private(Connection owner, Connection app, Connection... otherRoles) throws SQLException {
        assertEquals(2L, number(owner, """
            SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE n.nspname='public' AND p.proname IN ('guard_erasure_review_v105','guard_erasure_hold_v105')
                AND p.prosecdef AND p.proowner='safeai_migrator'::regrole
                AND p.proconfig=ARRAY['search_path=pg_catalog, pg_temp']
            """));
        assertTrue(flag(app, "SELECT has_table_privilege(current_user,'public.erasure_review_authorities_v105','SELECT')"));
        denied(app, "42501", "SELECT 1 FROM public.erasure_review_authorities_v105 FOR SHARE");
        denied(app, "42501", "UPDATE public.erasure_review_authorities_v105 SET enabled=true WHERE false");
        assertV108NoMutationOrGuardExecute(app);
        for (Connection role : otherRoles) assertV108NoMutationOrGuardExecute(role);
    }

    private static void assertV108NoMutationOrGuardExecute(Connection c) throws SQLException {
        assertFalse(flag(c, "SELECT has_table_privilege(current_user,'public.erasure_review_authorities_v105','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"));
        assertFalse(flag(c, "SELECT has_any_column_privilege(current_user,'public.erasure_review_authorities_v105','INSERT,UPDATE,REFERENCES')"));
        assertFalse(flag(c, "SELECT has_function_privilege(current_user,'public.guard_erasure_review_v105()','EXECUTE')"));
        assertFalse(flag(c, "SELECT has_function_privilege(current_user,'public.guard_erasure_hold_v105()','EXECUTE')"));
    for (String function : List.of("guard_erasure_request_fence_v108", "lock_erasure_hold_case_v108", "guard_erasure_review_reason_v108")) {
        assertFalse(flag(c, "SELECT has_function_privilege(current_user,'public." + function + "()','EXECUTE')"));
    }
    }

    @Test
    void nullDecisionReasonAndWithdrawalDuringHoldFailClosed() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrate(url);
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP);
                 var contender = connect(url, APP); var stale = connect(url, APP);
                 var pool = Executors.newSingleThreadExecutor()) {
                seed(owner);
                execute(owner, "INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret) VALUES(1,decode('" + PROOF_KEY + "','hex'))");
                execute(owner, "INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled) VALUES(" + lit(ORG) + "," + lit(LEGAL_A) + ",'REVIEW',true)");
                for (UUID legal : List.of(LEGAL_A, LEGAL_B)) {
                    execute(owner, "INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled) VALUES(" + lit(ORG) + "," + lit(legal) + ",'LEGAL',true)");
                }
                for (String action : List.of("APPROVED_REVIEW_ONLY", "REJECTED", "NEEDS_INFORMATION")) {
                    app.setAutoCommit(false);
                    try {
                        actorProof(app, LEGAL_A, action, 1, "v108-null-reason-test");
                        String sql = "UPDATE public.erasure_reviews_v105 SET review_state='" + action
                            + "',review_version=review_version+1,updated_by_user_id=" + lit(LEGAL_A)
                            + ",last_event_code='" + action + "',last_key='v108-null-reason-test',reason_code=NULL,updated_at=transaction_timestamp() WHERE request_id=" + lit(REQUEST);
                        var failure = expectSqlFailure(app, sql);
                        assertEquals("23514", failure.getSQLState(), failure.getMessage());
                        assertTrue(failure.getMessage().contains("V108_REVIEW_REASON_REQUIRED"), failure.getMessage());
                    } finally {
                        app.rollback();
                        app.setAutoCommit(true);
                    }
                }
                assertEquals(0L, number(owner, "SELECT review_version FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_review_events_v105 WHERE request_id=" + lit(REQUEST)));
                stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                stale.setAutoCommit(false);
                assertEquals(0L, number(stale, "SELECT count(*) FROM public.erasure_review_holds_v105 WHERE released_at IS NULL"));
                String withdraw = "UPDATE public.erasure_requests_v103 SET state='WITHDRAWN',version=version+1,withdraw_key='v108-withdraw-case',updated_at=transaction_timestamp() WHERE id=" + lit(REQUEST);
                app.setAutoCommit(false);
                try {
                    actorProof(app, LEGAL_A, "HOLD_APPLIED", 1, "v108-hold-withdraw");
                    execute(app, "INSERT INTO public.erasure_review_holds_v105(id,request_id,organization_id,reason_code,applied_by_user_id,apply_key) VALUES(" + lit(REVIEW_HOLD) + "," + lit(REQUEST) + "," + lit(ORG) + ",'LEGAL_REQUEST'," + lit(LEGAL_A) + ",'v108-hold-withdraw')");
                    long pid = number(contender, "SELECT pg_backend_pid()");
                    var future = pool.submit(() -> expectSqlFailure(contender, withdraw));
                    awaitLock(owner, pid);
                    app.commit();
                    var failure = future.get(10, TimeUnit.SECONDS);
                    assertEquals("23514", failure.getSQLState(), failure.getMessage());
                    assertTrue(failure.getMessage().contains("V108_ACTIVE_HOLD_BLOCKS_WITHDRAWAL"), failure.getMessage());
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                    pool.shutdownNow();
                    assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
                }
                denied(stale, "40001", withdraw);
                stale.rollback();
                var failure = expectSqlFailure(app, withdraw);
                assertEquals("23514", failure.getSQLState(), failure.getMessage());
                assertTrue(failure.getMessage().contains("V108_ACTIVE_HOLD_BLOCKS_WITHDRAWAL"), failure.getMessage());
                assertEquals("SUBMITTED", text(owner, "SELECT state FROM public.erasure_requests_v103 WHERE id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT version FROM public.erasure_requests_v103 WHERE id=" + lit(REQUEST)));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_review_holds_v105 WHERE id=" + lit(REVIEW_HOLD) + " AND released_at IS NULL"));
                app.setAutoCommit(false);
                try {
                    actorProof(app, LEGAL_B, "HOLD_RELEASED", 2, "v108-release-withdraw");
                    execute(app, "UPDATE public.erasure_review_holds_v105 SET released_at=transaction_timestamp(),released_by_user_id=" + lit(LEGAL_B) + ",release_key='v108-release-withdraw' WHERE id=" + lit(REVIEW_HOLD));
                    app.commit();
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                app.setAutoCommit(false);
                try {
                    execute(app, withdraw);
                    execute(app, "INSERT INTO public.erasure_request_events_v103(id,request_id,organization_id,actor_user_id,event_type,version) VALUES(gen_random_uuid()," + lit(REQUEST) + "," + lit(ORG) + "," + lit(OWNER) + ",'WITHDRAWN',2)");
                    app.commit();
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                assertEquals("WITHDRAWN", text(owner, "SELECT review_state FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                app.setAutoCommit(false);
                try {
                    actorProof(app, LEGAL_A, "HOLD_APPLIED", 4, "v108-postwithdraw-hold");
                    var deniedHold = expectSqlFailure(app, "INSERT INTO public.erasure_review_holds_v105(id,request_id,organization_id,reason_code,applied_by_user_id,apply_key) VALUES(gen_random_uuid()," + lit(REQUEST) + "," + lit(ORG) + ",'LEGAL_REQUEST'," + lit(LEGAL_A) + ",'v108-postwithdraw-hold')");
                    assertEquals("23514", deniedHold.getSQLState(), deniedHold.getMessage());
                    assertTrue(deniedHold.getMessage().contains("V108_HOLD_CASE_NOT_SUBMITTED"), deniedHold.getMessage());
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_review_holds_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals(4L, number(owner, "SELECT count(*) FROM public.erasure_review_events_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals("V106 test content", text(owner, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.chat_erasure_operations_v86"));
            }
        }
    }

    @Test
    void expiredPreviewCannotBeUsedByLongLivedTransaction() throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            migrate(url);
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP)) {
                seed(owner);
                for (boolean submit : List.of(false, true)) {
                    UUID preview = UUID.randomUUID(), request = UUID.randomUUID();
                    app.setAutoCommit(false);
                    try {
                        execute(app, "INSERT INTO public.erasure_previews_v103(id,organization_id,requester_user_id,resource_type,resource_id,operation,scope,resource_version,preview_digest,expires_at) SELECT " + lit(preview) + ",organization_id,user_id,'CHAT',id,'REDACT_CONTENT','FULL_CHAT_CONTENT',version,repeat('a',64),transaction_timestamp()+interval '3 seconds' FROM public.chat_sessions WHERE id=" + lit(CHAT));
                        String draft = "INSERT INTO public.erasure_requests_v103(id,organization_id,requested_by_user_id,resource_type,resource_id,operation,scope,reason_code,preview_id,preview_digest,expected_resource_version,idempotency_key,request_digest) SELECT " + lit(request) + ",organization_id,requester_user_id,resource_type,resource_id,operation,scope,'SELF_REQUEST',id,preview_digest,resource_version,'v108-expiry-" + request + "',repeat('b',64) FROM public.erasure_previews_v103 WHERE id=" + lit(preview);
                        if (submit) {
                            execute(app, draft);
                            execute(app, "INSERT INTO public.erasure_request_events_v103(id,request_id,organization_id,actor_user_id,event_type,version) VALUES(gen_random_uuid()," + lit(request) + "," + lit(ORG) + "," + lit(OWNER) + ",'DRAFT_CREATED',0)");
                        }
                        execute(app, "SELECT pg_sleep(3.1)");
                        assertTrue(flag(app, "SELECT expires_at > transaction_timestamp() AND expires_at <= clock_timestamp() FROM public.erasure_previews_v103 WHERE id=" + lit(preview)));
                        String sql = submit ? "UPDATE public.erasure_requests_v103 SET state='SUBMITTED',version=1,submitted_at=transaction_timestamp(),updated_at=transaction_timestamp(),submit_key='v108-expired-submit' WHERE id=" + lit(request) : draft;
                        var failure = expectSqlFailure(app, sql);
                        assertEquals("23514", failure.getSQLState(), failure.getMessage());
                        assertTrue(failure.getMessage().contains("V108_PREVIEW_NOT_LIVE"), failure.getMessage());
                    } finally {
                        app.rollback();
                        app.setAutoCommit(true);
                    }
                    assertEquals(0L, number(owner, "SELECT count(*) FROM public.erasure_requests_v103 WHERE id=" + lit(request)));
                    assertEquals(0L, number(owner, "SELECT count(*) FROM public.erasure_previews_v103 WHERE id=" + lit(preview)));
                }
            }
        }
    }

    private static void exercise(int upgradeFrom) throws Exception {
        try (var pg = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName(DB).withUsername("v106_test_root").withPassword(PASSWORD)) {
            pg.start();
            String url = pg.getJdbcUrl();
            bootstrap(url, pg.getUsername());
            long initialAuthority = 0;
            long initialHolds = 0;
            if (upgradeFrom != 0) {
                migrateToHistoricalVersion(url, Integer.toString(upgradeFrom));
                try (var c = connect(url, MIGRATOR)) {
                    assertEquals(upgradeFrom, number(c, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
                    seed(c);
                    if (upgradeFrom == 106) {
                        initialAuthority = epoch(c, "authority_epoch");
                        initialHolds = epoch(c, "all_holds_epoch");
                        assertTrue(initialAuthority > 0);
                    }
                    assertEquals("SUBMITTED", text(c, "SELECT state FROM public.erasure_requests_v103 WHERE id=" + lit(REQUEST)));
                }
            }
            if (upgradeFrom == 106) {
                try (var writer = connect(url, MIGRATOR)) {
                    writer.setAutoCommit(false);
                    try {
                        execute(writer, legacyIntent());
                        var failure = assertThrows(Exception.class, () -> migrate(url));
                        assertTrue(hasLockNotAvailableSqlState(failure), "V107 must refuse a live concurrent legacy writer: " + failure);
                    } finally {
                        writer.rollback();
                    }
                }
                try (var c = connect(url, MIGRATOR)) {
                    assertEquals(106L, number(c, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
                }
            }
            migrate(url);
            // Reapplying the actual migration/ACL entry point must neither loosen
            // privileges nor reset the epoch/cutover singleton.
            migrate(url);
            try (var owner = connect(url, MIGRATOR); var app = connect(url, APP);
                 var worker = connect(url, WORKER); var knowledge = connect(url, KNOWLEDGE_WORKER);
                 var operatorA = connect(url, OPERATOR_A); var operatorB = connect(url, OPERATOR_B)) {
                assertEquals(108L, number(owner, "SELECT count(*) FROM public.flyway_schema_history WHERE version IS NOT NULL AND success"));
                assertEquals("LEGACY_COMPATIBILITY", text(owner, "SELECT mode FROM public.chat_execution_cutover_v106"));
                assertEquals(initialAuthority, epoch(owner, "authority_epoch"));
                assertEquals(initialHolds, epoch(owner, "all_holds_epoch"));
                if (upgradeFrom == 0) seed(owner);
                assertEquals("SUBMITTED", text(owner, "SELECT state FROM public.erasure_requests_v103 WHERE id=" + lit(REQUEST)));
                assertEquals("PENDING", text(owner, "SELECT review_state FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
                assertEquals("V106 test content", text(owner, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
                for (var c : List.of(app, worker, knowledge, operatorA, operatorB)) assertPrivate(c);
                assertEquals(APP, text(app, "SELECT session_user"));
                assertEquals(WORKER, text(worker, "SELECT session_user"));
                assertEquals(OPERATOR_A, text(operatorA, "SELECT session_user"));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
                verifyV107Private(owner, app, worker, knowledge, operatorA, operatorB);
                verifyV108Private(owner, app, worker, knowledge, operatorA, operatorB);
                verifyScopedAuthorization(owner, app);
                verifyEpochs(url, owner, app, operatorA, operatorB);
                verifySealAndLedger(url, owner, app, worker);
                verifyExpiredWhileWaiting(url, owner, false);
                verifyExpiredWhileWaiting(url, owner, true);
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CUTOVER_SEALED'"));
                assertEquals(1L, number(owner, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CANDIDATE_REVOKED'"));
                denied(owner, "23514", "DELETE FROM public.erasure_gate_audit_v107");
                denied(owner, "23514", "TRUNCATE public.erasure_gate_catalog_v107");
                assertEquals("V106 test content", text(owner, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.chat_erasure_operations_v86"));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.knowledge_document_deletions_v78"));
                assertEquals(0L, number(owner, "SELECT count(*) FROM public.knowledge_base_deletions_v82"));
                assertEquals("AVAILABLE", text(owner, "SELECT erasure_state FROM public.chat_sessions WHERE id=" + lit(CHAT)));
            }
            migrate(url);
            verifyCatalogDrift(url);
            try (var owner = connect(url, MIGRATOR)) {
                assertEquals("SEALED", text(owner, "SELECT mode FROM public.chat_execution_cutover_v106"));
                assertEquals(2L, number(owner, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
                assertEquals(16L, number(owner, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
            }
        }
    }

    private static void bootstrap(String url, String root) throws SQLException {
        try (var c = connect(url, root)) {
            assertEquals(DB, text(c, "SELECT current_database()"));
            // Fixed literals and test-only credentials in a newly created container.
            execute(c, """
                CREATE ROLE safeai_migrator LOGIN PASSWORD 'v106-test-only-password';
                CREATE ROLE safeai_app LOGIN PASSWORD 'v106-test-only-password';
                CREATE ROLE safeai_chat_erasure_worker LOGIN PASSWORD 'v106-test-only-password';
                CREATE ROLE safeai_knowledge_erasure_worker LOGIN PASSWORD 'v106-test-only-password';
                CREATE ROLE safeai_chat_erasure_operator NOLOGIN;
                CREATE ROLE safeai_knowledge_erasure_operator NOLOGIN;
                CREATE ROLE v106_operator_a LOGIN PASSWORD 'v106-test-only-password';
                CREATE ROLE v106_operator_b LOGIN PASSWORD 'v106-test-only-password';
                GRANT safeai_chat_erasure_operator TO v106_operator_a,v106_operator_b;
                REVOKE CREATE ON SCHEMA public FROM PUBLIC;
                ALTER SCHEMA public OWNER TO safeai_migrator;
                REVOKE TEMPORARY ON DATABASE safeai_v106_disposable FROM PUBLIC;
                GRANT TEMPORARY ON DATABASE safeai_v106_disposable TO safeai_migrator;
                CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
                ALTER DEFAULT PRIVILEGES FOR ROLE safeai_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
                """);
        }
    }

    /**
     * Only production migrations must be discovered by the real production entry
     * point. Maven's test classpath also contains V0 for older Spring fixtures.
     * Keep all dependency classes delegated to the existing class loader; restrict
     * just the db/migration and db/acl resource namespaces to main output.
     */
    private static final class ProductionResources extends URLClassLoader {
        private static final String TEST_V0 = "db/migration/V0__test_chat_erasure_roles.sql";

        private ProductionResources() {
            super(new URL[]{DatabaseMigrationMain.class.getProtectionDomain().getCodeSource().getLocation()},
                DatabaseMigrationMain.class.getClassLoader());
        }

        private static boolean isDatabaseResource(String name) {
            return name.equals("db") || name.equals("db/")
                || name.equals("db/migration") || name.startsWith("db/migration/")
                || name.equals("db/acl") || name.startsWith("db/acl/");
        }

        @Override
        public URL getResource(String name) {
            return isDatabaseResource(name) ? findResource(name) : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return isDatabaseResource(name) ? findResources(name) : super.getResources(name);
        }

        private void assertIsolated() throws IOException {
            assertNotNull(getResource("db/migration/V1__init_schema.sql"));
            assertNotNull(getResource("db/migration/V105__erasure_review_intake.sql"));
            assertNotNull(getResource("db/migration/V106__chat_execution_permit_ledger.sql"));
            assertNotNull(getResource("db/migration/V107__chat_execution_hardening.sql"));
            assertNotNull(getResource("db/migration/V108__erasure_review_guard_lock_privileges.sql"));
            assertNotNull(getResource("db/acl/reviewed-migrations.sha256"));
            assertNull(getResource(TEST_V0), "Production migration path must never contain test V0");
            assertFalse(getResources(TEST_V0).hasMoreElements());
            assertEquals(1, Collections.list(getResources("db/migration")).size(),
                "Exactly one production migration resource directory is required");
        }
    }

    @Test
    void productionMigrationResourceIsolationExcludesTestV0WithoutChangingGlobalClasspath() throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        assertNotNull(original.getResource(ProductionResources.TEST_V0),
            "Positive control: ordinary integration test classpath includes V0");
        try (var resources = new ProductionResources()) {
            resources.assertIsolated();
            assertSame(original, Thread.currentThread().getContextClassLoader());
        }
        assertNotNull(original.getResource(ProductionResources.TEST_V0));
    }

    private static void migrateToHistoricalVersion(String url, String version) throws Exception {
        try (var resources = new ProductionResources()) {
            resources.assertIsolated();
            var flyway = Flyway.configure(resources).dataSource(url, MIGRATOR, PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .createSchemas(false).baselineOnMigrate(false).outOfOrder(false)
                .cleanDisabled(true).validateMigrationNaming(true).target(version)
                .ignoreMigrationPatterns("versioned:pending").load();
            flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
            flyway.migrate();
        }
    }

    private static void migrate(String url) throws Exception {
        var f = Files.createTempFile("safeai-v106-test-migration-password-", ".txt");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (var resources = new ProductionResources()) {
            resources.assertIsolated();
            Files.writeString(f, PASSWORD);
            // Flyway.configure() defaults to the calling thread context loader.
            // Exercise the real production entry point without allowing test V0.
            Thread.currentThread().setContextClassLoader(resources);
            try {
                DatabaseMigrationMain.run(new String[0], name -> switch (name) {
                    case "SAFEAI_MIGRATION_JDBC_URL" -> url;
                    case "SAFEAI_MIGRATION_PASSWORD_FILE" -> f.toString();
                    default -> null;
                });
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        } finally {
            Files.deleteIfExists(f);
        }
    }

    private static void seed(Connection c) throws SQLException {
        assertTrue(c.getAutoCommit(), "Fixture requires its own transaction");
        c.setAutoCommit(false);
        try {
            // V20 requires a user and at least one role to commit together.
            // Keep the complete case fixture atomic, including V103/V105 history.
            // V6 set_updated_at() uses now() (transaction start). Use the same
            // clock for INSERT timestamps, message, archive and preview expiry.
            // Wall-clock timestamps here would violate V4 after an UPDATE.
            execute(c, "INSERT INTO public.organizations(id,name,enabled,created_at,updated_at) VALUES (" + lit(ORG) + ",'V106 migration fixture',true,transaction_timestamp(),transaction_timestamp())");
            for (UUID id : List.of(OWNER, LEGAL_A, LEGAL_B)) {
                insertFixtureUser(c, id);
                try (var statement = c.prepareStatement("""
                    INSERT INTO public.user_roles(user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = 'ADMIN'
                    """)) {
                    statement.setObject(1, id);
                    assertEquals(1, statement.executeUpdate(), "Exactly one ADMIN role must be assigned");
                }
            }
            execute(c, "INSERT INTO public.chat_sessions(id,organization_id,user_id,title,title_source,created_at,updated_at,version) VALUES (" + lit(CHAT) + "," + lit(ORG) + "," + lit(OWNER) + ",'V106 chat','USER',transaction_timestamp(),transaction_timestamp(),0)");
            execute(c, "INSERT INTO public.chat_messages(id,session_id,organization_id,role,content,client_request_id,created_at,status,usage_status,pricing_status) VALUES (" + lit(MESSAGE) + "," + lit(CHAT) + "," + lit(ORG) + ",'USER','V106 test content',gen_random_uuid(),transaction_timestamp(),'COMPLETED','NOT_APPLICABLE','NOT_APPLICABLE')");
            execute(c, "UPDATE public.chat_sessions SET archived_at=transaction_timestamp(),archived_by_user_id=" + lit(OWNER) + ",version=version+1 WHERE id=" + lit(CHAT));
            execute(c, "INSERT INTO public.erasure_previews_v103(id,organization_id,requester_user_id,resource_type,resource_id,operation,scope,resource_version,preview_digest,expires_at) SELECT " + lit(PREVIEW) + "," + lit(ORG) + "," + lit(OWNER) + ",'CHAT',id,'REDACT_CONTENT','FULL_CHAT_CONTENT',version,repeat('a',64),transaction_timestamp()+interval '10 minutes' FROM public.chat_sessions WHERE id=" + lit(CHAT));
            execute(c, "INSERT INTO public.erasure_requests_v103(id,organization_id,requested_by_user_id,resource_type,resource_id,operation,scope,reason_code,preview_id,preview_digest,expected_resource_version,idempotency_key,request_digest) SELECT " + lit(REQUEST) + ",organization_id,requester_user_id,resource_type,resource_id,operation,scope,'SELF_REQUEST',id,preview_digest,resource_version,'v106-draft-fixture',repeat('b',64) FROM public.erasure_previews_v103 WHERE id=" + lit(PREVIEW));
            execute(c, "INSERT INTO public.erasure_request_events_v103(id,request_id,organization_id,actor_user_id,event_type,version) VALUES(gen_random_uuid()," + lit(REQUEST) + "," + lit(ORG) + "," + lit(OWNER) + ",'DRAFT_CREATED',0)");
            execute(c, "UPDATE public.erasure_requests_v103 SET state='SUBMITTED',version=1,submitted_at=transaction_timestamp(),updated_at=transaction_timestamp(),submit_key='v106-submit-fixture' WHERE id=" + lit(REQUEST));
            execute(c, "INSERT INTO public.erasure_request_events_v103(id,request_id,organization_id,actor_user_id,event_type,version) VALUES(gen_random_uuid()," + lit(REQUEST) + "," + lit(ORG) + "," + lit(OWNER) + ",'SUBMITTED',1)");
            assertFixtureTimestamps(c);
            c.commit();
        } finally {
            // Also handles deferred constraint errors raised by commit().
            c.rollback();
            c.setAutoCommit(true);
        }

        for (UUID id : List.of(OWNER, LEGAL_A, LEGAL_B)) {
            try (var statement = c.prepareStatement("""
                SELECT count(*) FROM public.users u
                JOIN public.user_roles ur ON ur.user_id = u.id
                JOIN public.roles r ON r.id = ur.role_id
                WHERE u.id = ? AND u.organization_id = ? AND u.enabled AND r.name = 'ADMIN'
                """)) {
                statement.setObject(1, id);
                statement.setObject(2, ORG);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1L, result.getLong(1), "Committed tenant ADMIN fixture");
                }
            }
        }
        assertFixtureTimestamps(c);
        assertRolelessUserRejected(c);
        assertReversedChatTimestampsRejected(c);
    }

    private static void insertFixtureUser(Connection c, UUID id) throws SQLException {
        try (var statement = c.prepareStatement("""
            INSERT INTO public.users(id, organization_id, email, password_hash,
                enabled, created_at, updated_at)
            VALUES (?, ?, ?, 'encoded-password', true, transaction_timestamp(), transaction_timestamp())
            """)) {
            statement.setObject(1, id);
            statement.setObject(2, ORG);
            statement.setString(3, id + "@example.test");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertRolelessUserRejected(Connection c) throws SQLException {
        // Negative control on every schema version exercised by seed(): V105,
        // V106 and V107 must still enforce the deferred V20 role invariant.
        UUID roleless = UUID.randomUUID();
        c.setAutoCommit(false);
        try {
            insertFixtureUser(c, roleless);
            var failure = assertThrows(SQLException.class, c::commit,
                "A user without a role must not commit");
            assertEquals("23514", failure.getSQLState(), failure.getMessage());
            assertTrue(failure.getMessage().contains("must have at least one role"),
                failure.getMessage());
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        try (var statement = c.prepareStatement("SELECT count(*) FROM public.users WHERE id = ?")) {
            statement.setObject(1, roleless);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "Rejected roleless user must not persist");
            }
        }
    }

    private static void assertFixtureTimestamps(Connection c) throws SQLException {
        try (var statement = c.prepareStatement("""
            SELECT count(*) FROM public.chat_sessions s
            JOIN public.chat_messages m ON m.session_id = s.id
                AND m.organization_id = s.organization_id
            WHERE s.id = ? AND s.organization_id = ? AND m.id = ?
                AND s.created_at = s.updated_at
                AND s.archived_at = s.created_at
                AND m.created_at = s.created_at
                AND s.archived_by_user_id = ? AND s.version = 1
            """)) {
            statement.setObject(1, CHAT);
            statement.setObject(2, ORG);
            statement.setObject(3, MESSAGE);
            statement.setObject(4, OWNER);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong(1),
                    "Atomic fixture must share the transaction clock through archive");
            }
        }
    }

    private static void assertReversedChatTimestampsRejected(Connection c) throws SQLException {
        // Negative control: V4 still rejects updated_at < created_at. Test an
        // INSERT because V6 intentionally normalizes updated_at on UPDATE.
        assertTrue(c.getAutoCommit(), "Negative fixture must be isolated");
        UUID invalidChat = UUID.randomUUID();
        try (var statement = c.prepareStatement("""
            INSERT INTO public.chat_sessions(id, organization_id, user_id, title,
                title_source, created_at, updated_at, version)
            VALUES (?, ?, ?, 'Invalid timestamp fixture', 'USER',
                transaction_timestamp() + interval '1 second', transaction_timestamp(), 0)
            """)) {
            statement.setObject(1, invalidChat);
            statement.setObject(2, ORG);
            statement.setObject(3, OWNER);
            var failure = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", failure.getSQLState(), failure.getMessage());
            assertTrue(failure.getMessage().contains("chk_chat_sessions_updated_after_created"),
                failure.getMessage());
        }
        try (var statement = c.prepareStatement("SELECT count(*) FROM public.chat_sessions WHERE id = ?")) {
            statement.setObject(1, invalidChat);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "Rejected timestamp fixture must not persist");
            }
        }
    }

    private static void assertPrivate(Connection c) throws SQLException {
        for (String table : List.of("chat_execution_cutover_v106", "erasure_execution_epochs_v106",
            "erasure_execution_permits_v106", "erasure_execution_identifiers_v106")) {
            assertFalse(flag(c, "SELECT has_table_privilege(current_user,'public." + table + "','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"));
            denied(c, "42501", "SELECT * FROM public." + table);
            denied(c, "42501", "DELETE FROM public." + table);
            denied(c, "42501", "TRUNCATE public." + table);
        }
        for (String fn : List.of("guard_chat_execution_cutover_v106", "guard_chat_execution_intent_v106",
            "guard_erasure_epochs_v106", "bump_erasure_epochs_v106", "guard_erasure_permit_v106",
            "reserve_erasure_identifiers_v106", "guard_erasure_identifiers_v106")) {
            assertFalse(flag(c, "SELECT has_function_privilege(current_user,'public." + fn + "()','EXECUTE')"));
            denied(c, "42501", "SELECT public." + fn + "()");
        }
        denied(c, "42501", "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
        denied(c, "42501", "UPDATE public.erasure_execution_epochs_v106 SET authority_epoch=0");
        denied(c, "42501", "INSERT INTO public.erasure_execution_permits_v106 DEFAULT VALUES");
        denied(c, "42501", "INSERT INTO public.erasure_execution_identifiers_v106 DEFAULT VALUES");
    }

    private static void verifyEpochs(String url, Connection c, Connection app, Connection opA, Connection opB) throws Exception {
        long a = epoch(c, "authority_epoch");
        execute(c, "INSERT INTO public.erasure_review_actor_keys_v105(key_id,secret) VALUES (1,decode('" + PROOF_KEY + "','hex'))");
        assertEquals(++a, epoch(c, "authority_epoch"));
        for (UUID legal : List.of(LEGAL_A, LEGAL_B)) {
            execute(c, "INSERT INTO public.erasure_review_authorities_v105(organization_id,user_id,authority,enabled) VALUES(" + lit(ORG) + "," + lit(legal) + ",'LEGAL',true)");
            assertEquals(++a, epoch(c, "authority_epoch"));
        }
        for (boolean enabled : List.of(false, true)) {
            execute(c, "UPDATE public.erasure_review_authorities_v105 SET enabled=" + enabled + " WHERE user_id=" + lit(LEGAL_A));
            assertEquals(++a, epoch(c, "authority_epoch"));
        }
        c.setAutoCommit(false);
        try {
            execute(c, "UPDATE public.erasure_review_authorities_v105 SET enabled=false WHERE user_id=" + lit(LEGAL_A));
            assertEquals(a + 1, epoch(c, "authority_epoch"));
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(a, epoch(c, "authority_epoch"));
        execute(app, "UPDATE public.platform_erasure_policy_v102 SET version=version+1 WHERE singleton");
        assertEquals(a + 1, epoch(c, "authority_epoch"));
        denied(c, "23514", "UPDATE public.erasure_execution_epochs_v106 SET authority_epoch=0");
        denied(c, "23514", "DELETE FROM public.erasure_execution_epochs_v106");
        denied(c, "23514", "TRUNCATE public.erasure_execution_epochs_v106");
        long h = epoch(c, "all_holds_epoch");
        long scopedHold = scope(c, "CHAT", ORG, CHAT);
        execute(c, "INSERT INTO public.chat_retention_holds_v86(id,chat_id,organization_id,reason_code) VALUES(" + lit(HOLD) + "," + lit(CHAT) + "," + lit(ORG) + ",'LEGAL_REQUEST')");
        assertEquals(++h, epoch(c, "all_holds_epoch"));
        assertEquals(++scopedHold, scope(c, "CHAT", ORG, CHAT));
        execute(opA, "SELECT public.chat_operator_approve_v86('RELEASE_HOLD'," + lit(HOLD) + ",'V106-TEST-HOLD')");
        denied(opA, "42501", "SELECT public.chat_operator_release_hold_v86(" + lit(HOLD) + ",'V106-TEST-HOLD')");
        execute(opB, "SELECT public.chat_operator_approve_v86('RELEASE_HOLD'," + lit(HOLD) + ",'V106-TEST-HOLD')");
        execute(opA, "SELECT public.chat_operator_release_hold_v86(" + lit(HOLD) + ",'V106-TEST-HOLD')");
        assertEquals(++h, epoch(c, "all_holds_epoch"));
        assertEquals(++scopedHold, scope(c, "CHAT", ORG, CHAT));
        try (var stale = connect(url, APP); var contender = connect(url, APP);
             var pool = Executors.newSingleThreadExecutor()) {
            stale.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            stale.setAutoCommit(false);
            assertEquals(0L, number(stale, "SELECT count(*) FROM public.erasure_review_holds_v105 WHERE released_at IS NULL"));
            app.setAutoCommit(false);
            try {
                actorProof(app, LEGAL_A, "HOLD_APPLIED", 1, "v106-apply-hold");
                execute(app, "INSERT INTO public.erasure_review_holds_v105(id,request_id,organization_id,reason_code,applied_by_user_id,apply_key) VALUES(" + lit(REVIEW_HOLD) + "," + lit(REQUEST) + "," + lit(ORG) + ",'LEGAL_REQUEST'," + lit(LEGAL_A) + ",'v106-apply-hold')");
                long pid = number(contender, "SELECT pg_backend_pid()");
                var task = pool.submit(() -> expectSqlFailure(contender, legacyIntent()));
                awaitLock(c, pid);
                app.commit();
                SQLException failure = task.get(10, TimeUnit.SECONDS);
                assertEquals("23514", failure.getSQLState(), failure.getMessage());
                assertTrue(failure.getMessage().contains("V107_COMBINED_LEGAL_HOLD"), failure.getMessage());
                denied(stale, "40001", legacyIntent());
            } finally {
                app.rollback();
                app.setAutoCommit(true);
                stale.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
        assertEquals(++h, epoch(c, "all_holds_epoch"));
        assertEquals(++scopedHold, scope(c, "CHAT", ORG, CHAT));
        var holdFailure = assertThrows(SQLException.class, () -> execute(app, legacyIntent()));
        assertEquals("23514", holdFailure.getSQLState());
        assertTrue(holdFailure.getMessage().contains("V107_COMBINED_LEGAL_HOLD"), holdFailure.getMessage());
        assertEquals(0L, number(c, "SELECT count(*) FROM public.chat_erasure_operations_v86"));
        assertEquals("V106 test content", text(c, "SELECT content FROM public.chat_messages WHERE id=" + lit(MESSAGE)));
        app.setAutoCommit(false);
        try {
            actorProof(app, LEGAL_B, "HOLD_RELEASED", 2, "v106-release-hold");
            execute(app, "UPDATE public.erasure_review_holds_v105 SET released_at=transaction_timestamp(),released_by_user_id=" + lit(LEGAL_B) + ",release_key='v106-release-hold' WHERE id=" + lit(REVIEW_HOLD));
            app.commit();
        } finally {
            app.rollback();
            app.setAutoCommit(true);
        }
        assertEquals(h + 1, epoch(c, "all_holds_epoch"));
        assertEquals(scopedHold + 1, scope(c, "CHAT", ORG, CHAT));
        assertEquals(2L, number(c, "SELECT hold_revision FROM public.erasure_reviews_v105 WHERE request_id=" + lit(REQUEST)));
    }

    private static void actorProof(Connection c, UUID actor, String action, int version, String key) throws Exception {
        String payload = "v1|" + number(c, "SELECT txid_current()") + "|" + actor + "|" + ORG + "|" + REQUEST + "|" + action + "|" + version + "|" + key;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(PROOF_KEY), "HmacSHA256"));
        String proof = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        try (var s = c.prepareStatement("SELECT set_config('safeai.erasure.review.actor_proof_v105',?,true)")) {
            s.setString(1, proof);
            s.execute();
        }
    }

    private static void verifySealAndLedger(String url, Connection c, Connection app, Connection worker) throws Exception {
        String intent = legacyIntent();
        UUID permit = UUID.randomUUID(), nonce = UUID.randomUUID();
        denied(c, "23514", record(permit, nonce, 0));
        // Real positive control: legacy intent works before seal, and blocks seal
        // even when in the same transaction. Roll back without redaction.
        c.setAutoCommit(false);
        try {
            execute(c, intent);
            var savepoint = c.setSavepoint();
            denied(c, "23514", "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
            c.rollback(savepoint);
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        // A concurrent writer must prevent the cutover, not slip through its snapshot.
        execute(c, "SET lock_timeout='2s'");
        app.setAutoCommit(false);
        try {
            execute(app, intent);
            denied(c, "55P03", "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
        } finally {
            app.rollback();
            app.setAutoCommit(true);
        }
        assertEquals("LEGACY_COMPATIBILITY", text(c, "SELECT mode FROM public.chat_execution_cutover_v106"));
        // A successful cutover and its audit row must commit or roll back together.
        c.setAutoCommit(false);
        try {
            long tx = number(c, "SELECT txid_current()");
            execute(c, "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
            assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CUTOVER_SEALED' AND subject_id IS NULL AND database_login='safeai_migrator' AND transaction_id=" + tx));
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals("LEGACY_COMPATIBILITY", text(c, "SELECT mode FROM public.chat_execution_cutover_v106"));
        assertEquals(0L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
        execute(c, "UPDATE public.chat_execution_cutover_v106 SET mode='SEALED'");
        assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CUTOVER_SEALED' AND subject_id IS NULL AND database_login='safeai_migrator'"));
        denied(c, "23514", "UPDATE public.chat_execution_cutover_v106 SET mode='LEGACY_COMPATIBILITY',sealed_at=NULL");
        denied(c, "23514", "DELETE FROM public.chat_execution_cutover_v106");
        denied(c, "23514", "TRUNCATE public.chat_execution_cutover_v106");
        denied(app, "23514", intent);
        denied(app, "23514", "UPDATE public.chat_sessions SET erasure_state='ERASING',version=version+1 WHERE id=" + lit(CHAT));
        execute(worker, "SELECT set_config('safeai.chat.erasure_operation','" + UUID.randomUUID() + "',false)");
        execute(worker, "SELECT set_config('safeai.chat.erasure_token','" + UUID.randomUUID() + "',false)");
        denied(worker, "23514", "UPDATE public.chat_messages SET content='[СОДЕРЖИМОЕ УДАЛЕНО]' WHERE id=" + lit(MESSAGE));
        denied(c, "23514", record(permit, nonce, -1));
        c.setAutoCommit(false);
        try {
            execute(c, record(permit, nonce, 0));
            assertEquals(8L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(0L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
        denied(c, "23514", record(permit, nonce, 0).replace(
            "statement_timestamp()+interval '2 minutes'", "statement_timestamp()"));
        denied(c, "23505", record(permit, permit, 0));
        execute(c, record(permit, nonce, 0));
        assertEquals(8L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
        denied(c, "23514", "UPDATE public.erasure_execution_permits_v106 SET state='CONSUMED'");
        denied(c, "23514", "UPDATE public.erasure_execution_permits_v106 SET owner_id=gen_random_uuid()");
        denied(c, "23514", "DELETE FROM public.erasure_execution_permits_v106");
        denied(c, "23514", "DELETE FROM public.erasure_execution_identifiers_v106");
        denied(c, "23514", "TRUNCATE public.erasure_execution_permits_v106,public.erasure_execution_identifiers_v106");
        // The second audit target has state, not mode. Exercise both the
        // real revocation and rollback before the permanent revocation.
        c.setAutoCommit(false);
        try {
            long tx = number(c, "SELECT txid_current()");
            execute(c, "UPDATE public.erasure_execution_permits_v106 SET state='REVOKED' WHERE permit_id=" + lit(permit));
            assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CANDIDATE_REVOKED' AND subject_id=" + lit(permit) + " AND database_login='safeai_migrator' AND transaction_id=" + tx));
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals("RECORDED_ONLY", text(c, "SELECT state FROM public.erasure_execution_permits_v106 WHERE permit_id=" + lit(permit)));
        assertEquals(0L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CANDIDATE_REVOKED'"));
        assertEquals(8L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106 WHERE permit_id=" + lit(permit)));
        execute(c, "UPDATE public.erasure_execution_permits_v106 SET state='REVOKED' WHERE permit_id=" + lit(permit));
        assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107 WHERE event_type='CANDIDATE_REVOKED' AND subject_id=" + lit(permit) + " AND database_login='safeai_migrator'"));
        denied(c, "23514", "UPDATE public.erasure_execution_permits_v106 SET state='RECORDED_ONLY'");
        denied(c, "23514", "UPDATE public.erasure_gate_audit_v107 SET recorded_at=clock_timestamp()");
        denied(c, "23514", "TRUNCATE public.erasure_gate_audit_v107");
        var forgedAudit = expectSqlFailure(c, "INSERT INTO public.erasure_gate_audit_v107(event_type,database_login,transaction_id) VALUES('CUTOVER_SEALED',session_user,txid_current())");
        assertEquals("23514", forgedAudit.getSQLState(), forgedAudit.getMessage());
        assertTrue(forgedAudit.getMessage().contains("V107_AUDIT_INSERT_REQUIRES_TRIGGER"), forgedAudit.getMessage());
        assertEquals(2L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
        // An incorrectly attached trigger must fail closed, not manufacture audit events.
        c.setAutoCommit(false);
        try {
            execute(c, "CREATE TEMP TABLE fixture_wrong_audit_target (id integer) ON COMMIT DROP");
            execute(c, "INSERT INTO fixture_wrong_audit_target VALUES(1)");
            execute(c, "CREATE TRIGGER fixture_wrong_audit AFTER UPDATE ON fixture_wrong_audit_target FOR EACH ROW EXECUTE FUNCTION public.audit_erasure_gate_v107()");
            var wrongTarget = expectSqlFailure(c, "UPDATE fixture_wrong_audit_target SET id=2");
            assertEquals("23514", wrongTarget.getSQLState(), wrongTarget.getMessage());
            assertTrue(wrongTarget.getMessage().contains("V107_AUDIT_TRIGGER_CONTEXT_INVALID"), wrongTarget.getMessage());
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
        assertEquals(2L, number(c, "SELECT count(*) FROM public.erasure_gate_audit_v107"));
        denied(c, "23505", record(permit, UUID.randomUUID(), 0));
        denied(c, "23505", record(UUID.randomUUID(), nonce, 0));
        denied(c, "23505", record(nonce, UUID.randomUUID(), 0));
        denied(c, "23505", record(UUID.randomUUID(), permit, 0));
        UUID usedProofRef = UUID.fromString(text(c, "SELECT identity_proof_ref::text FROM public.erasure_execution_permits_v106 WHERE permit_id=" + lit(permit)));
        denied(c, "23505", record(UUID.randomUUID(), usedProofRef, 0));
        assertEquals(1L, number(c, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
        // Two different transactions race over the same nonce. Exactly one commits.
        UUID shared = UUID.randomUUID();
        var started = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> race(url, shared, started, go));
            var two = pool.submit(() -> race(url, shared, started, go));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                go.countDown();
                assertEquals(1, one.get(20, TimeUnit.SECONDS) + two.get(20, TimeUnit.SECONDS));
            } finally {
                go.countDown();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
        assertEquals(2L, number(c, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
        assertEquals(16L, number(c, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
        // Normal restoration is not an execution operation.
        execute(app, "UPDATE public.chat_sessions SET archived_at=NULL,archived_by_user_id=NULL,version=version+1 WHERE id=" + lit(CHAT));
        assertTrue(flag(c, "SELECT archived_at IS NULL FROM public.chat_sessions WHERE id=" + lit(CHAT)));
    }

    private static String legacyIntent() {
        return "INSERT INTO public.chat_erasure_operations_v86(id,chat_id,organization_id,owner_user_id,requested_by_user_id,idempotency_key,expected_version,request_sha256) SELECT gen_random_uuid(),id,organization_id,user_id,user_id,'v106-intent-fixture',version,repeat('a',64) FROM public.chat_sessions WHERE id=" + lit(CHAT);
    }

    private static long scope(Connection c, String kind, UUID org, UUID resource) throws SQLException {
        try (var ps = c.prepareStatement("SELECT coalesce((SELECT epoch FROM public.erasure_scoped_epochs_v107 WHERE scope_kind=? AND organization_id=? AND resource_id=?),0)")) {
            ps.setString(1, kind);
            ps.setObject(2, org);
            ps.setObject(3, resource);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static void verifyScopedAuthorization(Connection owner, Connection app) throws SQLException {
        UUID other = UUID.randomUUID();
        execute(owner, "INSERT INTO public.organizations(id,name,enabled,created_at,updated_at) VALUES (" + lit(other) + ",'V107 other tenant',true,clock_timestamp(),clock_timestamp())");
        long first = scope(owner, "ORGANIZATION", ORG, ORG);
        long second = scope(owner, "ORGANIZATION", other, other);
        long global = epoch(owner, "authority_epoch");
        execute(app, "UPDATE public.users SET enabled=enabled WHERE id=" + lit(OWNER));
        assertEquals(first, scope(owner, "ORGANIZATION", ORG, ORG));
        assertEquals(global + 1, epoch(owner, "authority_epoch"));
        // A BEFORE trigger changes a security field not mentioned in UPDATE.
        // The actual OLD/NEW guard must catch this, while unrelated updates do not.
        execute(owner, """
            CREATE FUNCTION public.fixture_auth_side_effect() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN NEW.token_version:=OLD.token_version+1; RETURN NEW; END; $$;
            CREATE TRIGGER zz_fixture_auth_side_effect BEFORE UPDATE OF email ON public.users
                FOR EACH ROW EXECUTE FUNCTION public.fixture_auth_side_effect();
            """);
        try {
            execute(app, "UPDATE public.users SET email=email WHERE id=" + lit(OWNER));
            assertEquals(first + 1, scope(owner, "ORGANIZATION", ORG, ORG));
            assertEquals(second, scope(owner, "ORGANIZATION", other, other));
            assertEquals(global + 2, epoch(owner, "authority_epoch"));
            owner.setAutoCommit(false);
            try {
                execute(owner, "UPDATE public.users SET email=email WHERE id=" + lit(OWNER));
                assertEquals(first + 2, scope(owner, "ORGANIZATION", ORG, ORG));
            } finally {
                owner.rollback();
                owner.setAutoCommit(true);
            }
            assertEquals(first + 1, scope(owner, "ORGANIZATION", ORG, ORG));
            assertEquals(global + 2, epoch(owner, "authority_epoch"));
        } finally {
            execute(owner, "DROP TRIGGER zz_fixture_auth_side_effect ON public.users; DROP FUNCTION public.fixture_auth_side_effect()");
        }
        assertEquals(second, scope(owner, "ORGANIZATION", other, other));
        denied(owner, "23514", "UPDATE public.erasure_scoped_epochs_v107 SET epoch=0 WHERE organization_id=" + lit(ORG));
        denied(owner, "23514", "TRUNCATE public.erasure_scoped_epochs_v107");
    }

    private static void verifyV107Private(Connection owner, Connection... roles) throws SQLException {
        assertTrue(number(owner, "SELECT count(*) FROM public.erasure_gate_catalog_v107") > 25);
        for (var c : roles) {
            for (String table : List.of("erasure_scoped_epochs_v107", "erasure_gate_audit_v107", "erasure_gate_catalog_v107")) {
                denied(c, "42501", "SELECT * FROM public." + table);
                denied(c, "42501", "INSERT INTO public." + table + " DEFAULT VALUES");
                denied(c, "42501", "TRUNCATE public." + table);
            }
            for (String function : List.of("check_permit_after_reservation_v107", "lock_v105_chat_hold_v107", "guard_combined_chat_holds_v107", "guard_scoped_epochs_v107", "bump_scoped_epochs_v107", "audit_erasure_gate_v107", "reject_gate_evidence_change_v107", "guard_gate_audit_insert_v107")) {
                assertFalse(flag(c, "SELECT has_function_privilege(current_user,'public." + function + "()','EXECUTE')"));
            }
        }
    }

    private static void verifyExpiredWhileWaiting(String url, Connection owner, boolean uniqueWait) throws Exception {
        UUID permit = UUID.randomUUID();
        long count = number(owner, "SELECT count(*) FROM public.erasure_execution_permits_v106");
        long ids = number(owner, "SELECT count(*) FROM public.erasure_execution_identifiers_v106");
        try (var blocker = connect(url, MIGRATOR); var contender = connect(url, MIGRATOR);
             var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try {
                UUID nonce = UUID.randomUUID();
                if (uniqueWait) {
                    // The holder has already reserved the same nonce, but will roll back.
                    execute(blocker, record(UUID.randomUUID(), nonce, 0));
                } else {
                    execute(blocker, "SELECT 1 FROM public.erasure_execution_epochs_v106 WHERE singleton FOR UPDATE");
                }
                long pid = number(contender, "SELECT pg_backend_pid()");
                var task = pool.submit(() -> expectSqlFailure(contender,
                    record(permit, nonce, 0).replace("interval '2 minutes'", "interval '3 seconds'")));
                awaitLock(owner, pid);
                // The statement is already waiting; use PostgreSQL's clock to
                // outlive the three-second lifetime before releasing the lock.
                execute(owner, "SELECT pg_sleep(3.1)");
                blocker.rollback();
                var failure = task.get(10, TimeUnit.SECONDS);
                assertEquals("23514", failure.getSQLState());
                assertTrue(failure.getMessage().contains(uniqueWait ? "ERASURE_PERMIT_EXPIRED_AFTER_RESERVATION" : "ERASURE_PERMIT_EXPIRED_AFTER_LOCK"), failure.getMessage());
            } finally {
                blocker.rollback();
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            }
        }
        assertEquals(count, number(owner, "SELECT count(*) FROM public.erasure_execution_permits_v106"));
        assertEquals(ids, number(owner, "SELECT count(*) FROM public.erasure_execution_identifiers_v106"));
    }

    private static boolean hasLockNotAvailableSqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static SQLException expectSqlFailure(Connection c, String sql) {
        try {
            execute(c, sql);
        } catch (SQLException failure) {
            return failure;
        }
        throw new AssertionError("Protected statement unexpectedly committed");
    }

    private static void awaitLock(Connection observer, long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var ps = observer.prepareStatement("SELECT cardinality(pg_blocking_pids(?))>0")) {
            ps.setInt(1, Math.toIntExact(pid));
            while (true) {
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    if (rs.getBoolean(1)) return;
                }
                assertTrue(System.nanoTime() < deadline, "Concurrent statement must wait on a PostgreSQL lock");
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
    }

    private static void verifyCatalogDrift(String url) throws Exception {
        try (var c = connect(url, MIGRATOR)) {
            String reviewed = text(c, "SELECT pg_get_functiondef('public.audit_erasure_gate_v107()'::regprocedure)");
            execute(c, """
                CREATE OR REPLACE FUNCTION public.audit_erasure_gate_v107() RETURNS trigger
                LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
                BEGIN RETURN NULL; END;
                $$;
                """);
            try {
                var failure = assertThrows(SQLException.class, () -> migrate(url));
                assertTrue(failure.getMessage().contains("V108 audit function definition or ACL drift"), failure.getMessage());
            } finally {
                execute(c, reviewed);
            }
        }
        migrate(url);
        try (var c = connect(url, MIGRATOR)) {
            execute(c, "ALTER TABLE public.erasure_execution_permits_v106 DISABLE TRIGGER trg_permit_revoke_audit_v107");
            try {
                var failure = assertThrows(SQLException.class, () -> migrate(url));
                assertTrue(failure.getMessage().contains("V107 catalog definition drift"), failure.getMessage());
            } finally {
                execute(c, "ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER trg_permit_revoke_audit_v107");
            }
        }
        migrate(url);
        try (var c = connect(url, MIGRATOR)) {
            execute(c, "ALTER TABLE public.erasure_requests_v103 DISABLE TRIGGER zz_erasure_request_fence_v108");
            try {
                var failure = assertThrows(SQLException.class, () -> migrate(url));
                assertTrue(failure.getMessage().contains("V108 intake guard trigger drift"), failure.getMessage());
            } finally {
                execute(c, "ALTER TABLE public.erasure_requests_v103 ENABLE ALWAYS TRIGGER zz_erasure_request_fence_v108");
            }
        }
        migrate(url);
        try (var c = connect(url, MIGRATOR)) {
            execute(c, "ALTER FUNCTION public.guard_erasure_hold_v105() SECURITY INVOKER");
            try {
                assertThrows(SQLException.class, () -> migrate(url), "V108 must reject reverted definer attribute");
            } finally {
                execute(c, "ALTER FUNCTION public.guard_erasure_hold_v105() SECURITY DEFINER");
            }
        }
        migrate(url);
        try (var c = connect(url, MIGRATOR)) {
            execute(c, "ALTER TABLE public.erasure_execution_permits_v106 DISABLE TRIGGER zz_permit_after_reservation_v107");
            try {
                var failure = assertThrows(SQLException.class, () -> migrate(url));
                assertTrue(failure.getMessage().contains("V107 catalog definition drift"), failure.getMessage());
            } finally {
                execute(c, "ALTER TABLE public.erasure_execution_permits_v106 ENABLE ALWAYS TRIGGER zz_permit_after_reservation_v107");
            }
        }
        migrate(url);
        try (var c = connect(url, MIGRATOR)) {
            String drop = "DROP TRIGGER trg_v107_user_actual_auth_epoch_v107 ON public.users";
            String prefix = "CREATE TRIGGER trg_v107_user_actual_auth_epoch_v107 AFTER UPDATE ON public.users FOR EACH ROW WHEN (";
            String suffix = ") EXECUTE FUNCTION public.bump_erasure_epochs_v106(); ALTER TABLE public.users ENABLE ALWAYS TRIGGER trg_v107_user_actual_auth_epoch_v107";
            execute(c, drop + ";" + prefix + "false" + suffix);
            try {
                var failure = assertThrows(SQLException.class, () -> migrate(url));
                assertTrue(failure.getMessage().contains("V107 catalog definition drift"), failure.getMessage());
            } finally {
                execute(c, drop + ";" + prefix + "OLD.id IS DISTINCT FROM NEW.id OR OLD.organization_id IS DISTINCT FROM NEW.organization_id OR OLD.enabled IS DISTINCT FROM NEW.enabled OR OLD.token_version IS DISTINCT FROM NEW.token_version OR OLD.password_hash IS DISTINCT FROM NEW.password_hash" + suffix);
            }
        }
        migrate(url);
    }

    private static int race(String url, UUID nonce, CountDownLatch started, CountDownLatch go) throws Exception {
        try (var c = connect(url, MIGRATOR)) {
            execute(c, "SET statement_timeout='10s'");
            started.countDown();
            assertTrue(go.await(10, TimeUnit.SECONDS));
            try {
                execute(c, record(UUID.randomUUID(), nonce, 0));
                return 1;
            } catch (SQLException ex) {
                assertEquals("23505", ex.getSQLState(), ex.getMessage());
                return 0;
            }
        }
    }

    /**
     * Fixture records synthetic evidence only, not an approval or a real signed permit.
     */
    private static String record(UUID permit, UUID nonce, int epochOffset) {
        return """
            INSERT INTO public.erasure_execution_permits_v106
            (permit_id,request_id,organization_id,owner_id,chat_id,reviewer_id,execution_approver_id,
             case_version,resource_version,review_version,owner_token_version,reviewer_token_version,
             approver_token_version,organization_auth_version,policy_version,authority_epoch,all_holds_epoch,
             intake_digest,manifest_digest,evidence_digest,identity_proof_ref,manifest_ref,decision_ref,
             identity_nonce,manifest_nonce,decision_nonce,permit_nonce,issued_at,expires_at)
            SELECT %s,%s,%s,%s,%s,%s,%s,1,1,2,0,0,0,0,1,authority_epoch+(%d),all_holds_epoch,
                repeat('a',64),repeat('b',64),repeat('c',64),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),
                %s,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),statement_timestamp(),statement_timestamp()+interval '2 minutes'
            FROM public.erasure_execution_epochs_v106 WHERE singleton
            """.formatted(lit(permit), lit(REQUEST), lit(ORG), lit(OWNER), lit(CHAT), lit(LEGAL_A), lit(LEGAL_B), epochOffset, lit(nonce));
    }

    private static Connection connect(String url, String login) throws SQLException {
        return DriverManager.getConnection(url, login, PASSWORD);
    }

    private static String lit(UUID id) {
        return "'" + id + "'::uuid";
    }

    private static long epoch(Connection c, String name) throws SQLException {
        return switch (name) {
            case "authority_epoch" -> number(c, "SELECT authority_epoch FROM public.erasure_execution_epochs_v106");
            case "all_holds_epoch" -> number(c, "SELECT all_holds_epoch FROM public.erasure_execution_epochs_v106");
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(20);
            s.execute(sql);
        }
    }

    private static long number(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            long v = r.getLong(1);
            assertFalse(r.wasNull());
            return v;
        }
    }

    private static boolean flag(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            boolean v = r.getBoolean(1);
            assertFalse(r.wasNull());
            return v;
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    private static void denied(Connection c, String sqlState, String sql) {
        var ex = assertThrows(SQLException.class, () -> execute(c, sql), sql);
        assertEquals(sqlState, ex.getSQLState(), ex.getMessage());
    }
}
