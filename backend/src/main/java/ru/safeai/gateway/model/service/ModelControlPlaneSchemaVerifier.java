package ru.safeai.gateway.model.service;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Fail-fast production schema verifier for model-control-plane invariants that
 * Java relies on before any provider side effect can be attempted.
 */
@Component
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class ModelControlPlaneSchemaVerifier implements SmartInitializingSingleton {

    private final JdbcTemplate jdbc;

    public ModelControlPlaneSchemaVerifier(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = jdbc.query(
                """
                with required(name, present) as (
                    values
                        ('model_route_decisions',
                         to_regclass('public.model_route_decisions') is not null),
                        ('model_route_reserved_requests',
                         to_regclass('public.model_route_reserved_requests') is not null),
                        ('model_route_prepared_requests_v62',
                         to_regclass('public.model_route_prepared_requests_v62') is not null),
                        ('model_cost_commitment_ledger_v62',
                         to_regclass('public.model_cost_commitment_ledger_v62') is not null),
                        ('model_route_budget_commitments_v64',
                         to_regclass('public.model_route_budget_commitments_v64') is not null),
                        ('knowledge_rag_context_receipts_v64',
                         to_regclass('public.knowledge_rag_context_receipts_v64') is not null)
                )
                select name from required where not present order by name
                """,
                (rs, row) -> rs.getString(1)
        );
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Model Control Plane database schema is incomplete: missing " + missing
            );
        }

        Boolean exactTurnConstraint = jdbc.queryForObject(
                """
                select exists (
                    select 1
                    from pg_trigger t
                    join pg_class c on c.oid = t.tgrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    join pg_constraint con on con.oid = t.tgconstraint
                    where n.nspname = 'public'
                      and c.relname = 'model_route_decisions'
                      and t.tgname = 'ctrg_model_route_decision_turn_v45'
                      and not t.tgisinternal
                      and con.condeferrable
                      and t.tgenabled in ('O', 'A')
                )
                """,
                Boolean.class
        );
        if (!Boolean.TRUE.equals(exactTurnConstraint)) {
            throw new IllegalStateException(
                    "Required deferrable constraint trigger "
                            + "ctrg_model_route_decision_turn_v45 is missing"
            );
        }

        Boolean v64PreparedColumns = jdbc.queryForObject(
                """
                select count(*) = 4
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'model_route_prepared_requests_v62'
                  and column_name in (
                      'knowledge_base_id',
                      'retrieval_run_id',
                      'rag_context_sha256',
                      'binding_version'
                  )
                """,
                Boolean.class
        );
        if (!Boolean.TRUE.equals(v64PreparedColumns)) {
            throw new IllegalStateException(
                    "V64 prepared-request provenance columns are missing"
            );
        }

        Boolean v64BudgetState = jdbc.queryForObject(
                """
                select exists (
                    select 1
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name = 'model_route_budget_commitments_v64'
                      and column_name = 'cost_state'
                )
                """,
                Boolean.class
        );
        if (!Boolean.TRUE.equals(v64BudgetState)) {
            throw new IllegalStateException(
                    "V64 budget commitment cost_state is missing"
            );
        }

        // Fail closed on drift in persisted guard enforcement. Historical V65
        // NOT VALID constraints are intentionally not required to be validated;
        // PostgreSQL nevertheless enforces them for all NEW writes.
        List<String> inactiveGuards = jdbc.query("""
                with expected(table_name, trigger_name) as (
                    values
                    ('model_catalog_entries',
                     'trg_model_catalog_entries_immutable_v45'),
                    ('model_route_prepared_requests_v62',
                     'trg_model_route_prepared_requests_guard_v64'),
                    ('model_execution_attempts',
                     'trg_execution_attempt_prepared_binding_v65'),
                    ('model_route_budget_commitments_v64',
                     'trg_model_route_budget_commitments_immutable_v64'),
                    ('knowledge_rag_context_receipts_v64',
                     'trg_knowledge_rag_context_receipt_immutable_v64')
                )
                select expected.trigger_name
                from expected
                where not exists (
                    select 1
                    from pg_trigger t
                    where t.tgrelid = to_regclass(
                        'public.' || expected.table_name
                    )
                      and t.tgname = expected.trigger_name
                      and not t.tgisinternal
                      and t.tgenabled in ('O', 'A')
                )
                order by expected.trigger_name
                """, (rs, index) -> rs.getString(1));
        if (!inactiveGuards.isEmpty()) {
            throw new IllegalStateException(
                    "Model Control Plane database guards inactive: "
                            + inactiveGuards
            );
        }

        List<String> missingChecks = jdbc.query("""
                with required(table_name, name, must_validate) as (
                    values
                    ('model_catalog_entries',
                     'chk_model_catalog_pricing_scheme_v66', true),
                    ('model_route_prepared_requests_v62',
                     'chk_model_route_prepared_current_binding_v65', false),
                    ('model_execution_attempts',
                     'chk_model_execution_attempt_lifecycle_v66', true),
                    ('model_route_budget_commitments_v64',
                     'chk_model_route_budget_commitments_state_shape_v64', true)
                )
                select required.name
                from required
                where not exists (
                    select 1
                    from pg_constraint c
                    where c.conrelid = to_regclass(
                        'public.' || required.table_name
                    )
                      and c.conname = required.name
                      and c.contype = 'c'
                      and (not required.must_validate or c.convalidated)
                )
                order by required.name
                """, (rs, index) -> rs.getString(1));
        if (!missingChecks.isEmpty()) {
            throw new IllegalStateException(
                    "Model Control Plane CHECK constraints missing: "
                            + missingChecks
            );
        }

        Boolean priceSchemeNotNullable = jdbc.queryForObject("""
                select exists (
                    select 1 from pg_attribute a
                    where a.attrelid = to_regclass('public.model_catalog_entries')
                      and a.attname = 'pricing_scheme'
                      and a.attnotnull
                      and not a.attisdropped
                )
                """, Boolean.class);
        if (!Boolean.TRUE.equals(priceSchemeNotNullable)) {
            throw new IllegalStateException(
                    "Model catalog pricing_scheme is missing or nullable"
            );
        }
    }
}
