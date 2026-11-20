# Project State

Updated: 2026-11-20 (Asia/Calcutta).

## Working rules

- Keep this file and STOCKGUARD_PROJECT_GUIDE.md at root. English in code/docs; Hinglish/English in chat.
- User wants continuous implementation without quiz/confirmation gates; explanations in updates/docs. Keep README/state short.
- Latest scope: complete core backend now; Redis/Docker later; combined tests after implementation.

## Implemented

- Java 25, Boot 4.1.2, Maven Wrapper 3.10.0; PostgreSQL/Flyway V1-V3, catalog/inventory, JWT auth/admin roles. User manually verified registration, admin login and product creation.
- Reservation create/detail/own history, owner-only confirm/cancel, orders/detail/own history. Admin can read others' records but cannot perform their lifecycle actions. Only CUSTOMER creates holds.
- Defaults: hold 5m, max quantity 2, max pending 3 (overdue pending counts), worker interval 5s/batch 100. Config validated. Single product per hold; immutable item snapshots.
- Durable idempotency on create/confirm/cancel: user+operation+key, canonical SHA-256 hash, response replay; successful results retained indefinitely. Failed transactions consume no key. GET shows current state; retries replay original snapshots.
- Explicit pessimistic SQL locks through JdbcTemplate for workflow, existing JPA for catalog/users; shared DataSource/JpaTransactionManager. Lock order and file purposes in docs/architecture.md.
- Expiry claims one reservation per transaction with SKIP LOCKED; scheduled batches catch up after outages. Clock injection for tests; stock released only on commit, confirm/cancel reject at deadline.
- Swagger UI at /swagger-ui/index.html; walkthrough in docs/workflow.md. GitHub verify workflow prepared, not run remotely.

## Verification

- Final verify: 43 tests passed, zero failures/errors/skips on disposable PostgreSQL 18 and Java 25; packaged JAR built.
- 100 customers/1 unit: 1 success, 99 conflicts. 100 customers/7 units: 7 successes, 93 conflicts. Same-user caps and concurrent duplicate retries verified.
- Ownership, snapshots, confirm/cancel/expiry races, exact deadline and deadline after lock wait, multiple expiry workers, transaction rollback and stock adjustments verified.
- Packaged app stopped/restarted with isolated test data: scheduled expiry restored availability 8->10; original idempotency result replayed after restart without holding stock again. Observed release delay ~14.17s in this deliberate downtime/startup scenario, not a performance guarantee.
- TEST_DB_* enables random-schema DB tests; otherwise those skip. Health-only test mocks repositories/JdbcTemplate. Verification app processes and disposable PostgreSQL stopped; temporary schemas removed.

## Environment / next

- Local credentials/signing key stay in ignored config/application-local.properties; never print them. Existing local-profile run unchanged. Other machines require JWT_SECRET (Base64 of 32+ random bytes).
- Redis cache/rate limits, Docker Compose/Testcontainers, deployment and Git initialization/remote CI remain pending. No payment, direct orders, multi-item reservations, inventory ledger, refresh/revocation or fairness guarantee.
- Next: user demo via Swagger, then Redis/Docker phase when requested. No need to repeat individual manual tests before proceeding.

