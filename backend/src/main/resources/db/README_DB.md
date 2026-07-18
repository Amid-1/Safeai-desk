# SafeAI schema hardening patch

## Files

- `V13__identity_refresh_and_message_integrity.sql` — place in `backend/src/main/resources/db/migration/`.
- `V1000__seed_local_demo_data.sql` — replace the existing file in `backend/src/main/resources/db/local-migration/`.

## Application changes still required

1. Normalize email before persistence and lookup with `email.trim().toLowerCase(Locale.ROOT)`.
2. Trim organization names before persistence while preserving case.
3. Refresh rotation must run in one transaction under a pessimistic lock:
    - lock the current token;
    - reject a token that already has `replaced_by_token_id`;
    - insert the successor with the same `user_id` and `token_family_id`;
    - revoke the predecessor and set its replacement exactly once.
4. Add a service integration test proving that an existing replacement cannot be reassigned. V13 now also enforces this in PostgreSQL.
5. Confirm that every successfully persisted `ASSISTANT/COMPLETED` message has a non-null provider model. Usage counters may remain nullable.

## Minimum PostgreSQL/Testcontainers migration tests

Run Flyway against a real empty PostgreSQL instance, not H2.

- Clean install: V1–V13; then Hibernate schema validation.
- Local install: V1–V13 plus V1000; verify fixed organization/user IDs and role ownership.
- Tenant integrity: reject cross-organization session, message and user-rollup rows.
- Refresh integrity: reject self replacement, shared replacement, unrevoked predecessor, different user, different family, reassignment and cycles.
- Identity integrity: reject uppercase/padded email and padded organization name; reject normalized duplicates.
- Message integrity: reject provider metadata on USER messages and completed ASSISTANT messages without model.
- Audit integrity: reject unknown event type; default null/omitted details to `{}`; preserve audit row and null `user_id` after user deletion.
- Rollup integrity: reject negative values, blank model and mismatched `total_tokens`.

## Index decisions

- `idx_user_roles_user_id` is removed because the primary key `(user_id, role_id)` already covers its access pattern.
- Boolean quota indexes are intentionally left unchanged; remove or replace them only after measuring real plans.
- Do not add alternate usage index orders until `UsageQueryRepository` SQL is verified with `EXPLAIN (ANALYZE, BUFFERS)`.