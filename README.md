# StockGuard

Inventory reservation and order API built with Java 25, Spring Boot 4.1.2, PostgreSQL, Flyway, Spring Security and Swagger UI.

## Run locally (PowerShell, project root)

This machine uses ignored `config/application-local.properties` for database credentials and the JWT signing key. PostgreSQL must be running; Docker is not needed yet.

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dspring-boot.run.profiles=local' spring-boot:run
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- Health: http://localhost:8080/actuator/health
- Stop with Ctrl+C. Restart after code changes; Flyway applies V1-V3 automatically.
- Other machines: configure JDK 25, DB_URL, DB_USERNAME, DB_PASSWORD and JWT_SECRET. `.env.example` is reference only, not automatically loaded. Never edit applied migrations.
- [Login and first-admin setup](docs/authentication.md) | [Complete workflow walkthrough](docs/workflow.md) | [Architecture and file map](docs/architecture.md)

## Implemented

| Area | Features |
|---|---|
| Authentication | CUSTOMER registration, password hashing, 15-minute JWT, ADMIN authorization |
| Catalog/inventory | Public active-product reads, admin create/PATCH/stock adjustment, validation/pagination |
| Reservations | Create/read/history, owner-only confirm/cancel, quantity and pending limits |
| Orders | Immutable price/name snapshots, owner/admin detail access, own history |
| Retry safety | Durable idempotency for reservation create/confirm/cancel |
| Expiry | Scheduled worker, safe concurrent claims, restart catch-up |

Defaults: 5-minute holds, max 2 units per reservation, max 3 pending per customer, expiry worker every 5 seconds in batches of up to 100. Stock remains held until expiry commits. Only CUSTOMER tokens can create reservations; ADMIN may read but cannot confirm/cancel someone else's hold.

## Tests

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' verify
```

For all database tests set TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD to a dedicated PostgreSQL database/role with schema creation permission. Without TEST_DB_URL database tests explicitly skip. Tests use isolated random schemas.

Verified on PostgreSQL 18/Java 25: **43 tests, zero failures/errors/skips**. Includes 100 concurrent customers competing for 1 and 7 units, duplicate requests, ownership, user caps, lifecycle races, rollback and deadline checks. Packaged app restart also verified: scheduled expiry released stock and persisted idempotency replay created no new hold. GitHub CI configuration is prepared but has not run remotely.

## Boundaries / remaining work

- Redis, Docker Compose and Testcontainers are deferred; deployment and GitHub delivery remain pending.
- One product per reservation; no cart, direct-order shortcut or payment integration.
- No FIFO/fair-allocation guarantee; account limits cannot stop multiple accounts/coordinated customers.
- Idempotency successes are retained indefinitely; product/stock writes are not idempotent. No inventory movement ledger yet.
- Token refresh/revocation, email verification and rate limiting are not implemented.

[Project guide](STOCKGUARD_PROJECT_GUIDE.md) and [current state](PROJECT_STATE.md) remain at root. `.m2` and `target` are ignored generated files.
