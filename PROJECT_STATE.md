# Project State

Updated: 2026-11-20 (Asia/Calcutta).

## Working rules

- Keep this file and STOCKGUARD_PROJECT_GUIDE.md at root. English in code/docs; Hinglish/English in chat.
- User wants continuous implementation without quiz/confirmation gates; explanations in updates/docs. Keep README/state short.
- Latest scope: Redis phase complete locally; verify remote CI and finish delivery next. Frontend comes after backend/testing/delivery. User runs all Git commands.

## Implemented

- Java 25, Boot 4.1.2, Maven Wrapper 3.10.0; PostgreSQL/Flyway V1-V4, catalog/inventory, JWT auth/admin roles. User manually verified registration, admin login and product creation.
- Reservation create/detail/own history, owner-only confirm/cancel, orders/detail/own history. Admin can read others' records but cannot perform their lifecycle actions. Only CUSTOMER creates holds.
- Defaults: hold 5m, max quantity 2, max pending 3 (overdue pending counts), worker interval 5s/batch 100. Config validated. Single product per hold; immutable item snapshots.
- Durable idempotency on create/confirm/cancel: user+operation+key, canonical SHA-256 hash, response replay; successful results retained indefinitely. Failed transactions consume no key. GET shows current state; retries replay original snapshots.
- Explicit pessimistic SQL locks through JdbcTemplate for workflow, existing JPA for catalog/users; shared DataSource/JpaTransactionManager. Lock order and file purposes in docs/architecture.md.
- Expiry claims one reservation per transaction with SKIP LOCKED; scheduled batches catch up after outages. Clock injection for tests; stock released only on commit, confirm/cancel reject at deadline.
- Swagger UI at /swagger-ui/index.html; walkthrough in docs/workflow.md. GitHub verify workflow now uses Testcontainers; updated workflow not verified remotely.
- Dockerfile: Maven Wrapper/JDK 25 build, non-root JRE 25 runtime. Compose: separate PostgreSQL 18, persistent named volume, app on localhost:8081, health checks. Generated secrets in ignored .env.docker; setup in docs/docker.md.
- Redis 8.2: versioned product-detail metadata cache (60s TTL), fresh PostgreSQL stock/active/version query on every read; list uncached. V4/JPA @Version invalidates by version; fills occur after commit to prevent rollback pollution.
- Atomic Lua fixed windows: login/register share 20 attempts/IP/60s; reservation creation 10/customer/60s. Filter runs after authorization; forwarded headers ignored. Quota gives 429/Retry-After. Redis outage: catalog falls back, protected POSTs give 503; confirm/cancel/expiry remain independent. Features disabled in original local profile unless REDIS_ENABLED=true; enabled in Compose. Details in docs/redis.md.

## Verification

- Latest verify: 53 tests passed, zero failures/errors/skips on Testcontainers PostgreSQL 18/Redis 8.2 and Java 25; packaged JAR built. TEST_DB_* was unset, proving the automatic Docker path.
- 100 customers/1 unit: 1 success, 99 conflicts. 100 customers/7 units: 7 successes, 93 conflicts. Same-user caps and concurrent duplicate retries verified.
- Ownership, snapshots, confirm/cancel/expiry races, exact deadline and deadline after lock wait, multiple expiry workers, transaction rollback and stock adjustments verified.
- Packaged app stopped/restarted with isolated test data: scheduled expiry restored availability 8->10; original idempotency result replayed after restart without holding stock again. Observed release delay ~14.17s in this deliberate downtime/startup scenario, not a performance guarantee.
- TEST_DB_* optionally selects an external dedicated database; otherwise Testcontainers starts disposable PostgreSQL. Missing Docker fails rather than skipping database tests. Health-only test mocks repositories/JdbcTemplate; test schemas are isolated and removed.
- Test JVM and container runtime use UTC; the PostgreSQL image rejected the host's legacy Asia/Calcutta alias. Maven required selecting installed JDK 25 because JAVA_HOME still pointed at JDK 23.
- Compose image built successfully after retrying a transient Maven Central TLS failure. Container health/Swagger, registration/login, admin product creation, reservation and confirmation passed. App runs as non-root with UTC.
- Compose down/up recreated both containers; product, inventory and order survived in the named volume. Temporary smoke-test records removed. App/PostgreSQL containers left healthy for the user; Swagger at localhost:8081.
- Ten Redis tests cover metadata query avoidance/live stock, version invalidation/stale fill, rollback, corrupted/expired cache, quotas/identity/security ordering, idempotent retries, 100 concurrent decisions across two limiter objects (7 allowed), outage and recovery. Packaged Redis-enabled API also passed cache invalidation and fresh stock after reserve/cancel; temporary records removed. App/PostgreSQL/Redis left healthy.

## Environment / next

- Local credentials/signing key stay in ignored config/application-local.properties; never print them. Existing local-profile run unchanged. Other machines require JWT_SECRET (Base64 of 32+ random bytes).
- User created and initially pushed https://github.com/Pratik5545/stockguard. Latest Redis changes await the user's commit/push; remote CI result remains unverified.
- Deployment remains pending; frontend is explicitly planned after backend/testing/delivery. No payment, direct orders, multi-item reservations, inventory ledger, refresh/revocation or fairness guarantee. Redis restarts reset quotas/cache; no performance benchmark claim.
- Next: user commits/pushes Redis changes, checks GitHub Actions, then deployment preparation. Frontend is the final phase. No need to repeat individual manual tests.

