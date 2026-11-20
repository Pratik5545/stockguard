# StockGuard

Inventory reservation and order API built with Java 25, Spring Boot 4.1.2, PostgreSQL, Flyway, Spring Security and Swagger UI.

## Run locally (PowerShell, project root)

This machine uses ignored `config/application-local.properties` for database credentials and the JWT signing key. PostgreSQL must be running; this local profile does not require Docker.

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dspring-boot.run.profiles=local' spring-boot:run
```

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- Health: http://localhost:8080/actuator/health
- Stop with Ctrl+C. Restart after code changes; Flyway applies V1-V4 automatically.
- Other machines: configure JDK 25, DB_URL, DB_USERNAME, DB_PASSWORD and JWT_SECRET. `.env.example` is reference only, not automatically loaded. Never edit applied migrations.
- [Login and first-admin setup](docs/authentication.md) | [Complete workflow walkthrough](docs/workflow.md) | [Architecture and file map](docs/architecture.md)

## Run with Docker

Start Docker Desktop, then run from the project root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-Docker.ps1
docker compose --env-file .env.docker up --build --detach --wait
```

Swagger: http://localhost:8081/swagger-ui/index.html. Compose starts the app, PostgreSQL and Redis. Its separate PostgreSQL database persists in a named volume. Stop with `docker compose --env-file .env.docker down`. [Docker setup](docs/docker.md) | [Redis behavior](docs/redis.md).

Verified: container health/Swagger, authentication, reservation-to-order flow and database persistence after Compose container recreation.

## Implemented

| Area | Features |
|---|---|
| Authentication | CUSTOMER registration, password hashing, 15-minute JWT, ADMIN authorization |
| Catalog/inventory | Public active-product reads, admin create/PATCH/stock adjustment, validation/pagination |
| Reservations | Create/read/history, owner-only confirm/cancel, quantity and pending limits |
| Orders | Immutable price/name snapshots, owner/admin detail access, own history |
| Retry safety | Durable idempotency for reservation create/confirm/cancel |
| Expiry | Scheduled worker, safe concurrent claims, restart catch-up |
| Redis | Versioned product metadata cache with fresh DB stock; shared auth/reservation request limits |

Defaults: 5-minute holds, max 2 units per reservation, max 3 pending per customer, expiry worker every 5 seconds in batches of up to 100. Stock remains held until expiry commits. Only CUSTOMER tokens can create reservations; ADMIN may read but cannot confirm/cancel someone else's hold.

## Tests

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' verify
```

Docker must be running: Testcontainers starts disposable PostgreSQL 18 and Redis 8.2. TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD optionally select a dedicated PostgreSQL database/role with schema creation permission; Redis tests still require Docker. Database schemas are isolated; unavailable Docker fails tests instead of skipping them.

Verified on Testcontainers PostgreSQL 18/Redis 8.2/Java 25: **53 tests, zero failures/errors/skips**. Includes 100-customer stock races, ownership/retries/caps/lifecycle rollback, cache hits/live stock/version invalidation and atomic limits/outage recovery. Packaged API checks also passed. Earlier restart testing verified expiry catch-up and durable replay. Updated GitHub CI still needs remote verification.

## Boundaries / remaining work

- Deployment and updated remote CI verification remain pending. Frontend is planned after backend/testing/delivery work. GitHub repository: [Pratik5545/stockguard](https://github.com/Pratik5545/stockguard).
- One product per reservation; no cart, direct-order shortcut or payment integration.
- No FIFO/fair-allocation guarantee; account limits cannot stop multiple accounts/coordinated customers.
- Idempotency successes are retained indefinitely; product/stock writes are not idempotent. No inventory movement ledger yet.
- Token refresh/revocation and email verification are not implemented. Redis is optional in the original local profile and enabled in Compose. When enabled: auth quota 20/IP/minute, reservation-create quota 10/customer/minute; Redis failure gives catalog fallback and 503 for protected writes.

[Project guide](STOCKGUARD_PROJECT_GUIDE.md) and [current state](PROJECT_STATE.md) remain at root. `.m2` and `target` are ignored generated files.
