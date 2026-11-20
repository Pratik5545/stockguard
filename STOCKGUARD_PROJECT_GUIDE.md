# StockGuard — Inventory Reservation & Order Management API

> **Project type:** Portfolio-grade backend REST API  
> **Primary language:** Java  
> **Framework:** Spring Boot  
> **Primary database:** PostgreSQL  
> **Supporting infrastructure:** Redis, Docker, Flyway, JUnit, Testcontainers  
> **Development style:** Learn the engineering concepts, implement incrementally, test claims, and document decisions.

---

## 1. Project overview

StockGuard is a backend system for managing products, inventory, temporary stock reservations, and customer orders.

It is intentionally more than a basic CRUD application. Its central engineering problem is **correctly handling concurrent requests for limited inventory**.

Imagine a store has only one unit of a product remaining. One hundred customers send a request at almost the same time to reserve that product. A weak implementation may let multiple requests read `1` as the available quantity and then all attempt to reserve it. That creates overselling.

StockGuard must enforce this invariant:

> **At no point may the system reserve or sell more units than the inventory actually contains.**

The project will also demonstrate practical backend concerns:

- REST API design and HTTP semantics.
- Layered application design and separation of concerns.
- Relational database modelling and SQL.
- Transactions, concurrency control, and data consistency.
- Authentication, authorization, and ownership checks.
- Idempotency and safe client retries.
- Time-based reservation expiry.
- Redis caching and rate limiting.
- Automated unit, integration, and concurrency testing.
- Database migrations, Docker, CI, API documentation, and deployment.

### Why we are building it

The goal is to create a project that gives a reviewer concrete evidence of backend engineering ability. Instead of claiming that we know Spring Boot, PostgreSQL, Redis, or testing, the repository should demonstrate how and why we used them.

The project should be understandable and defensible in an interview. Every major design choice must have a reason, a test, and documentation.

### What StockGuard is not

The first version is **not**:

- A microservices system.
- A full e-commerce website with a frontend.
- A real payment-processing platform.
- A distributed system using Kafka, Kubernetes, or dozens of services.
- A project where Redis replaces PostgreSQL as the source of truth.

We will start with a well-structured **modular monolith**. We can add complexity only when there is a clear requirement for it.

---

## 2. Product scope and actors

### Actors

**Customer**
- Register and log in.
- Browse products.
- Create a reservation for one or more products.
- View their reservations and orders.
- Confirm or cancel an eligible reservation.

**Administrator**
- Create, update, and deactivate products.
- Adjust inventory.
- View inventory and order information.

### Main business flow

1. An administrator creates a product and adds stock.
2. A customer browses available products.
3. The customer requests a reservation for a quantity of one or more products.
4. StockGuard atomically checks and reserves the requested stock.
5. The reservation remains `PENDING` until its expiry time.
6. The customer confirms it before expiry, creating an order.
7. Alternatively, the customer cancels it, or the reservation expires automatically. In either case, reserved stock is released.

The first usable release can support one product per reservation to keep the initial concurrency implementation focused. Multi-item reservations can be added as a deliberate extension. The data model should not make that extension unnecessarily difficult.

---

## 3. Core business rules and invariants

These rules are more important than the framework code. All implementation and tests must preserve them.

### Inventory model

For each product, maintain:

- `on_hand_quantity`: physical quantity currently owned by the store.
- `reserved_quantity`: part of the physical quantity temporarily held by pending reservations.
- `available_quantity`: derived value, `on_hand_quantity - reserved_quantity`.

Do not independently update a stored `available_quantity` unless there is a clearly justified reason. Deriving it avoids a third number that can drift out of sync.

Required invariant:

```text
on_hand_quantity >= 0
reserved_quantity >= 0
reserved_quantity <= on_hand_quantity
available_quantity = on_hand_quantity - reserved_quantity
```

### Reservation state machine

A reservation has one of these states:

```text
PENDING ── confirm ──> CONFIRMED
   │
   ├────── cancel ───> CANCELLED
   │
   └────── timeout ──> EXPIRED
```

Terminal states (`CONFIRMED`, `CANCELLED`, `EXPIRED`) must not transition back to `PENDING` or to another terminal state.

Only the reservation owner may confirm or cancel it; the ADMIN role alone does not grant these permissions. Owner/Admin access to read endpoints does not grant permission to perform lifecycle actions. A reservation may be confirmed or cancelled only while PENDING and when `now < expires_at`; at `now >= expires_at`, reject those actions and let the expiry workflow release stock. Use an injected `Clock` so time-dependent tests do not rely on sleeping. Check the deadline after acquiring the reservation lock, immediately before the guarded transition.

### Reservation abuse and allocation policy

Reservations represent a temporary checkout hold, not adding a product to a shopping cart. The initial release does not implement a cart.

Before implementing reservation creation, select and document a short configurable hold duration, a maximum quantity per product per reservation, and a maximum number of PENDING reservations per user. Include overdue PENDING records in that count until their stock has actually been released. Enforce quantity and pending-count limits transactionally; concurrent requests from one user must not bypass the cap. An idempotent replay must not consume another slot. Do not extend a hold merely because the client retries or reads it.

Add selected-endpoint rate limits in the Redis phase. Test repeated reserve/cancel attempts, simultaneous requests near the per-user cap, oversized requests, and attempts to renew an existing hold. Implemented defaults (2026-11-20): 5-minute holds, maximum quantity 2, maximum 3 PENDING reservations per customer; all are configurable. Overdue PENDING holds count until expiry commits. Redis rate limiting remains deferred.

Stock correctness and fair allocation are separate guarantees. The initial allocation policy accepts eligible requests as database contention permits; it does not promise FIFO ordering or equal purchase chances. Per-account limits and short expiry reduce abuse but cannot prevent one person using multiple accounts or coordinated customers holding all stock. Record this limitation in the README. Verified identity, bot detection, waiting rooms, and lotteries are possible later product features, not initial guarantees.

### Inventory transitions

| Event | `on_hand_quantity` | `reserved_quantity` | Result |
|---|---:|---:|---|
| Reserve `q` units | unchanged | `+q` | Available stock falls by `q` |
| Confirm `q` units | `-q` | `-q` | Physical stock is sold; available stock stays unchanged at confirmation |
| Cancel `q` units | unchanged | `-q` | Available stock increases by `q` |
| Expire `q` units | unchanged | `-q` | Available stock increases by `q` |
| Add stock `q` | `+q` | unchanged | Available stock increases by `q` |

Every transition must be atomic with its related reservation/order state change.

### No overselling

If only `q` units are available, the total quantity successfully reserved by concurrent requests must never exceed `q`.

A product-list response showing available stock is informational. **The reservation transaction in PostgreSQL is the final authority**. Never trust a cached stock count when deciding whether to accept a reservation.

---

## 4. Technology choices and why they are used

| Technology | Responsibility | Why it belongs in this project |
|---|---|---|
| Java | Main application language | Demonstrates object-oriented design, type safety, and backend implementation |
| Spring Boot | REST application | Controllers, dependency injection, configuration, validation, security, and application integration |
| Spring Data JPA | Persistence abstraction | Repository operations and entity mapping; learn the SQL underneath rather than treating JPA as magic |
| PostgreSQL | System of record | Durable relational data, constraints, transactions, and concurrency control |
| Redis | Supporting fast data layer | Product/catalog caching and shared rate limiting; optionally short-lived coordination data where justified |
| Spring Security | Authentication and authorization | Protect endpoints and enforce roles/ownership |
| JWT | Access-token format | Stateless access-token authentication for the API |
| Flyway | Schema migrations | Version and reproduce database changes |
| JUnit 5 | Tests | Automated verification |
| Mockito | Unit-test doubles | Isolate application logic where appropriate |
| Testcontainers | Integration-test dependencies | Test against a real PostgreSQL instance rather than assuming an in-memory database behaves identically |
| Maven | Build and dependency management | Reproducible build and dependency lifecycle |
| Docker Compose | Local infrastructure | Start the API, PostgreSQL, and Redis consistently |
| OpenAPI / Swagger UI | API documentation | Make endpoints easy to inspect and manually test |
| GitHub Actions | CI | Automatically build and test every pull request or push |

Use a currently supported Java LTS release and a compatible stable Spring Boot release at project start. Record exact versions in the README; do not blindly copy version numbers from an old tutorial.

### Why Redis is included

Redis must solve an actual problem rather than exist as a resume keyword.

Initial Redis use cases:

1. **Catalog cache:** Cache frequently requested product details or catalog pages with a short TTL. Invalidate or update the relevant cache entries after product changes.
2. **Rate limiting:** Apply shared request limits across API instances, for example per user or per IP for selected endpoints.

Possible later use:

3. **Background-work coordination:** Redis can support a queue or short-lived coordination mechanism if the project later needs one. PostgreSQL remains the durable record of reservations and orders.

Do not use Redis as the authoritative inventory store. Do not rely on a Redis TTL alone to release inventory. A cache can disappear or become stale; the durable business state belongs in PostgreSQL.

---

## 5. High-level architecture

Start with a modular monolith. One Spring Boot application contains distinct feature areas.

```text
Client (Postman / Swagger / future frontend)
                  |
                  v
          REST Controllers
                  |
        DTO validation + mapping
                  |
                  v
          Application Services
       (business rules + transactions)
             /          \
            v            v
      PostgreSQL        Redis
      system of         cache / rate
        record           limiting
            ^
            |
       Flyway migrations

Scheduled expiry worker ---> PostgreSQL
```

Typical request path:

```text
HTTP request
  -> Controller
  -> Request DTO validation
  -> Application service
  -> Domain/business-rule checks
  -> Repository / database operation
  -> Response DTO
  -> HTTP response
```

Keep controllers thin. They should deal with HTTP concerns, validation, and response mapping. Business rules belong in services/domain logic. Database access belongs behind repositories or focused persistence components.

### Suggested package structure

Prefer packages grouped by feature rather than putting every controller in one giant package and every service in another.

```text
com.example.stockguard
├── StockGuardApplication.java
├── common
│   ├── config
│   ├── exception
│   ├── response
│   └── util
├── auth
│   ├── controller
│   ├── dto
│   ├── service
│   └── security
├── user
│   ├── entity
│   └── repository
├── catalog
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── mapper
│   ├── repository
│   └── service
├── inventory
│   ├── entity
│   ├── repository
│   └── service
├── reservation
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   └── service
├── order
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   └── service
├── idempotency
├── infrastructure
│   ├── cache
│   ├── ratelimit
│   └── scheduler
└── reporting
```

Do not create empty packages for every future feature on day one. Add them when their implementation begins.

---

## 6. Initial relational data model

Treat this as a starting model to review and refine during the database-design phase. Do not generate every entity immediately.

### `users`

| Column | Purpose |
|---|---|
| `id` | Primary key (UUID is a reasonable choice) |
| `email` | Unique login identifier |
| `password_hash` | Encoded password; never store plaintext passwords |
| `role` | `CUSTOMER` or `ADMIN` for the initial version |
| `created_at` | Creation timestamp |
| `updated_at` | Last update timestamp |

### `products`

| Column | Purpose |
|---|---|
| `id` | Primary key |
| `sku` | Unique stock-keeping identifier |
| `name` | Product name |
| `description` | Optional description |
| `price` | Decimal monetary amount; do not use floating point for money |
| `active` | Whether customers can order it |
| `created_at`, `updated_at` | Audit timestamps |

### `inventory`

| Column | Purpose |
|---|---|
| `product_id` | Primary key and FK to product |
| `on_hand_quantity` | Physical stock quantity |
| `reserved_quantity` | Quantity held by pending reservations |
| `version` | Optional optimistic-lock version if using JPA versioning for relevant operations |
| `updated_at` | Last stock update |

Add database `CHECK` constraints for non-negative quantities and `reserved_quantity <= on_hand_quantity`.

### `reservations`

| Column | Purpose |
|---|---|
| `id` | Reservation ID |
| `user_id` | Customer who owns it |
| `status` | `PENDING`, `CONFIRMED`, `CANCELLED`, `EXPIRED` |
| `expires_at` | Reservation deadline, stored as a timezone-aware timestamp |
| `created_at`, `updated_at` | Lifecycle timestamps |

### `reservation_items` (for multi-product reservations)

| Column | Purpose |
|---|---|
| `id` | Primary key |
| `reservation_id` | FK to reservation |
| `product_id` | FK to product |
| `quantity` | Positive requested quantity |
| `unit_price` | Price snapshot at reservation time |

Store the agreed unit price in the reservation/order item. Historical orders must not change because a product's current price changes later.

### `orders`

| Column | Purpose |
|---|---|
| `id` | Order ID |
| `reservation_id` | Unique link to the confirmed reservation |
| `user_id` | Customer who owns the order |
| `status` | Initially `PLACED`; future states can be added deliberately |
| `total_amount` | Monetary total calculated from item snapshots |
| `created_at` | Order creation time |

### `order_items`

Copy product identifier/SKU, product name if needed, quantity, and unit-price snapshot. This preserves what the customer actually ordered even if the product is later edited or deactivated.

### `idempotency_records`

Use a durable database table for the idempotency guarantee.

Suggested fields:

- `id`
- `user_id` (or a well-defined client identity)
- `idempotency_key`
- `operation_scope` (for example, `CREATE_RESERVATION`)
- `request_hash`
- `response_status`
- `response_body` (JSON)
- `created_at`
- `expires_at` (retention/cleanup policy, not a reservation expiry)

Add a unique constraint across the chosen identity fields, such as `(user_id, operation_scope, idempotency_key)`. Define and test what happens when the same key is reused with a different request body.

### Later tables, not day-one requirements

- `inventory_movements` for an auditable record of stock additions, corrections, reservations, sales, cancellations, and expirations.
- `outbox_events` if reliable asynchronous publication to external systems becomes a requirement.
- `audit_logs` for administrative actions if needed.

An inventory movement ledger is a valuable extension because it helps explain *why* the current stock balance has its value. Do not add it until the basic reservation workflow is correct.

---

## 7. API design plan

Use a versioned base path such as `/api/v1`. Exact request/response shapes should be designed and documented before implementation.

### Authentication

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Register customer |
| `POST` | `/api/v1/auth/login` | Public | Authenticate and return access token |

### Catalog

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/products` | Public | List active products with pagination/filtering |
| `GET` | `/api/v1/products/{id}` | Public | Product details and informational availability |
| `POST` | `/api/v1/admin/products` | Admin | Create product and initial inventory record |
| `PATCH` | `/api/v1/admin/products/{id}` | Admin | Update product details |
| `POST` | `/api/v1/admin/products/{id}/stock-adjustments` | Admin | Add or correct stock with validation |

### Reservations

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/reservations` | Customer | Reserve stock; require an `Idempotency-Key` header |
| `GET` | `/api/v1/reservations/{id}` | Owner/Admin | Read reservation |
| `GET` | `/api/v1/me/reservations` | Customer | List own reservations |
| `POST` | `/api/v1/reservations/{id}/confirm` | Owner | Confirm pending reservation and create order |
| `POST` | `/api/v1/reservations/{id}/cancel` | Owner | Cancel pending reservation and release stock |

### Orders

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/orders/{id}` | Owner/Admin | Order details |
| `GET` | `/api/v1/me/orders` | Customer | List own orders |

Use correct HTTP status codes, pagination, request validation, and a consistent error format. Consider Spring's `ProblemDetail` support for structured error responses. Never expose stack traces, password hashes, tokens, or internal database details in an API error.

A customer must not access another customer's reservation or order simply by guessing its UUID. Every resource lookup must enforce ownership or administrator permission.

---

## 8. Critical engineering designs to learn

### 8.1 Concurrency-safe stock reservation

A naïve implementation is unsafe:

```text
1. Read available stock.
2. If enough, continue.
3. Update reserved stock.
```

Two transactions can read the same old value before either updates it. Both may think the stock is available.

We will compare two correct database approaches and choose one deliberately:

**Approach A — Conditional atomic update**

Conceptually:

```sql
UPDATE inventory
SET reserved_quantity = reserved_quantity + :quantity
WHERE product_id = :productId
  AND on_hand_quantity - reserved_quantity >= :quantity;
```

Check the affected-row count:

- `1` row updated: the reservation quantity was secured.
- `0` rows updated: product does not exist in inventory or enough stock was not available; distinguish these cases if the API needs a precise error.

This is a strong candidate for the first implementation because the condition and increment happen in one database statement. Learn how PostgreSQL handles concurrent updates and rechecks the row predicate.

**Approach B — Pessimistic row lock**

Read the inventory row using a row-level lock (for example, `SELECT ... FOR UPDATE`), check availability, and update while holding the lock inside a transaction.

This is easier to reason about initially in some workflows but holds a lock for the duration of the transaction. Learn lock scope, deadlocks, and transaction duration.

Do not mix approaches casually. Implement one, test it, then compare the alternative in a small experiment or technical note.

**Multi-item reservations:** If one reservation contains several products, lock/update product rows in a deterministic order (for example, ascending product ID) to reduce deadlock risk. All stock updates and reservation inserts must be in one database transaction. If any item cannot be reserved, roll back the entire reservation so the customer does not get a partial reservation unintentionally.

### 8.2 Confirming a reservation

Within one transaction:

1. Load and lock the reservation as needed.
2. Verify that the caller owns it; an administrator cannot confirm another user's reservation solely because of their role.
3. Verify `status = PENDING` and that it is not expired.
4. Transition the reservation to `CONFIRMED`.
5. Decrease both `on_hand_quantity` and `reserved_quantity` for its items.
6. Create the order and its item price snapshots.
7. Commit everything together.

If any step fails, the state transition, inventory changes, and order creation must roll back together.

### 8.3 Cancelling and expiring a reservation

Cancellation and expiry must each release reserved stock exactly once.

The service must perform a guarded state transition from `PENDING` to `CANCELLED` or `EXPIRED`. Only the transaction that successfully changes the pending state should release the stock. Repeated cancel/expiry attempts must not decrement `reserved_quantity` more than once.

For expiry, use a scheduled worker that queries due pending reservations in batches. PostgreSQL can support worker-safe batch claiming with row locks such as `FOR UPDATE SKIP LOCKED`. Process a reservation's state transition and stock release in one transaction. The confirmation path and expiry worker must coordinate through the same row/state rules so they cannot both win.

Do not use `Thread.sleep()` or one in-memory timer per reservation. Do not rely on Redis key expiry to perform the durable stock release.

**Expiry delay policy:** The deadline makes a reservation ineligible for confirmation even if the worker has not processed it yet. Reserved stock remains held until the expiry transaction commits; do not advertise it as available or subtract it from reserved stock based only on the timestamp. Configure and document the worker interval and batch size. Processing delay also depends on backlog, locks, and outages, so the interval is not a guaranteed maximum release delay. On restart, the worker must catch up on overdue reservations. Record the observed delay during testing and expose worker failures/backlog when adding operational monitoring.

Test the exact deadline, an overdue reservation before worker processing, delayed processing, restart/catch-up, repeated worker runs, and confirmation racing with expiry. Assert that no expired hold is confirmed, and that stock is released exactly once when processing commits.

### 8.4 Idempotency

Networks fail. A client may submit a reservation request, lose the response, and retry. Without idempotency, the retry may create a second reservation.

Proposed contract:

- Client sends an `Idempotency-Key` with the create-reservation request.
- First accepted request stores the key, a hash of the request, and the resulting response as part of the durable operation.
- A retry with the same key and same request returns the original result rather than creating another reservation.
- Reuse of the same key with a different request body returns a conflict response (for example, HTTP `409`).
- Concurrent requests with the same key must not create duplicate reservations.

Use a unique database constraint and a transactionally sound implementation. Do not make Redis the only store for idempotency records. Work through concurrent duplicate requests in tests before claiming this feature is complete.

### 8.5 Redis caching

Use a cache-aside flow for product details:

```text
GET product
  -> check Redis
      -> cache hit: return cached data
      -> cache miss: read PostgreSQL
                    -> place result in Redis with TTL
                    -> return result
```

On product update/deactivation, invalidate or refresh the appropriate cache key. Document the consistency trade-off and TTL.

Never use cached availability as the authoritative check for a reservation. Inventory reservation must go through the transactional PostgreSQL path.

### 8.6 Rate limiting

Choose a small number of endpoints that need limits, such as login and reservation creation. Define the identity (user ID, IP, or a combination), window, maximum requests, and response behaviour. Use Redis so limits are shared if more than one API instance runs.

Do not rate-limit every endpoint arbitrarily. Explain the threat or resource concern each limit addresses.

---

## 9. Step-by-step implementation roadmap

**Rule for every phase:** Learn the concept first, design the smallest slice, implement it yourself, test it, and only then move forward. Do not generate the entire application in one prompt.

### Phase 0 — Understand the domain and prepare the repository

Learn:
- What inventory, reservation, order, and SKU mean.
- Why an order and a reservation are different records.
- Why available stock is derived.
- What invariants are and why business rules must be explicit.

Tasks:
- Create the GitHub repository `stockguard`.
- Keep `STOCKGUARD_PROJECT_GUIDE.md` and `PROJECT_STATE.md` at the repository root, as requested by the learner. Do not move or duplicate them into another folder.
- Write a short README containing the project problem and scope.
- Draw the reservation state machine.
- Create a list of user stories and acceptance criteria.

**Exit criteria:** You can explain the whole reservation lifecycle without discussing Spring code.

### Phase 1 — Design the database

Learn:
- Primary/foreign keys, unique/check constraints, indexes.
- One-to-one, one-to-many, and many-to-one relationships.
- Normalization and why order items need price snapshots.
- Database transactions and isolation at a conceptual level.

Tasks:
- Draw an ER diagram.
- Agree on the initial tables and constraints.
- Decide ID type, money type, and timestamp convention.
- Create the first Flyway migration.
- Inspect the actual schema in PostgreSQL.

**Exit criteria:** Explain every table, relationship, constraint, and important index.

### Phase 2 — Spring Boot foundation and catalog API

Learn:
- Spring Boot project structure and Maven lifecycle.
- Inversion of Control and dependency injection.
- Controller, service, repository responsibilities.
- DTOs versus entities.
- Bean Validation and exception handling.
- HTTP methods, status codes, pagination, and REST resource design.

Tasks:
- Create the Spring Boot application.
- Connect PostgreSQL using environment-based configuration.
- Add Flyway and verify migrations run.
- Implement create/read/update product endpoints.
- Implement pagination and validation.
- Add consistent error responses.
- Test endpoints using Postman or Swagger UI.

**Exit criteria:** Catalog API works, validation is tested, schema is migration-controlled, and no entity is exposed directly as an API contract.

### Phase 3 — Authentication and authorization

Learn:
- Password hashing versus encryption.
- Authentication versus authorization.
- JWT structure, signature validation, expiry, and trade-offs.
- Spring Security filter chain and method/request authorization.
- Resource ownership and insecure direct object reference (IDOR) risks.

Tasks:
- Add registration and login.
- Hash passwords with an appropriate password encoder.
- Issue and verify access tokens.
- Add customer/admin roles.
- Restrict admin product and inventory operations.
- Ensure customers can access only their own reservations and orders.
- Add tests for unauthenticated, unauthorized, and cross-user requests.

**Exit criteria:** Security tests prove the intended role and ownership rules.

### Phase 4 — Inventory and basic reservation workflow

Learn:
- `@Transactional` and transaction boundaries.
- Atomic SQL updates and row-level locks.
- Lost updates and race conditions.
- Database constraints as a final line of defence.

Tasks:
- Implement inventory creation and stock adjustments.
- Implement a basic reservation for one product.
- Choose conditional atomic update or pessimistic locking.
- Insert reservation and change inventory in the same transaction.
- Implement reservation retrieval.
- Add failure handling for unavailable stock.

**Exit criteria:** A normal reservation works, a rejected reservation changes no stock, and a database constraint prevents invalid quantities.

### Phase 5 — Prove concurrency correctness

Learn:
- What a race condition is.
- How concurrent transactions interact in PostgreSQL.
- Why a test with sequential requests does not prove concurrency safety.
- How to use an executor, synchronization barrier/latch, and many simultaneous requests in a test.

Tasks:
- Use Testcontainers with real PostgreSQL.
- Seed one product with one available unit.
- Launch many concurrent reservation attempts.
- Assert exactly one succeeds.
- Assert the final database state is valid.
- Repeat with a stock quantity greater than one.
- Add a test for multi-item all-or-nothing behaviour when that feature is implemented.

**Exit criteria:** A repeatable concurrency test demonstrates that successful reservations never exceed stock. Record the test setup and result in the README.

### Phase 6 — Confirmation, cancellation, and orders

Learn:
- State machines and valid/invalid transitions.
- Atomic multi-table updates.
- Why order history needs immutable price snapshots.

Tasks:
- Confirm a pending reservation and create an order in one transaction.
- Decrease on-hand and reserved quantities correctly.
- Cancel a pending reservation and release stock exactly once.
- Reject invalid repeated transitions.
- Implement customer order history and ownership checks.

**Exit criteria:** All state transitions and inventory effects are covered by integration tests.

### Phase 7 — Idempotency

Learn:
- At-least-once delivery and why clients retry.
- Idempotent operation semantics.
- Unique constraints and concurrent duplicate requests.
- Request hashing and response replay.

Tasks:
- Define the API contract for `Idempotency-Key`.
- Create the idempotency table and unique constraint.
- Persist the operation result safely.
- Reject a reused key with a different payload.
- Test sequential and concurrent duplicate submissions.

**Exit criteria:** Retrying the same request does not create duplicate reservations or orders, and key misuse is handled clearly.

### Phase 8 — Reservation expiry

Learn:
- Scheduled jobs and batch processing.
- Clock injection and deterministic time tests.
- State-transition races.
- `FOR UPDATE SKIP LOCKED` and why multiple workers need safe claiming.

Tasks:
- Add an expiry duration and `expires_at`.
- Build a scheduled batch worker.
- Claim due pending reservations safely.
- Mark them expired and release stock in the same transaction.
- Test confirmation racing with expiry.
- Test that repeated worker runs do not release stock twice.

**Exit criteria:** Expiry works after an application restart and remains correct if the worker retries.

### Phase 9 — Redis caching and rate limiting

Learn:
- Cache-aside, TTL, invalidation, and stale data.
- Why cache and source-of-truth databases have different responsibilities.
- Fixed-window/sliding-window/token-bucket rate-limiting concepts.

Tasks:
- Add Redis to Docker Compose.
- Cache product reads.
- Invalidate cache on relevant writes.
- Measure cache-hit and cache-miss behaviour.
- Add rate limits to selected endpoints.
- Test behaviour when Redis is unavailable; choose and document a safe fallback for each feature.

**Exit criteria:** Redis demonstrably reduces repeated read work or enforces shared limits, without influencing the correctness of stock reservation.

### Phase 10 — Quality, documentation, and delivery

Learn:
- Unit tests versus integration tests.
- Test pyramid and what mocks cannot prove.
- CI pipelines and reproducible environments.
- Structured logs, health checks, and basic metrics.

Tasks:
- Review unit and integration test coverage.
- Add validation/security/regression tests.
- Add Actuator health checks and useful structured logging.
- Add OpenAPI descriptions and example requests.
- Create Dockerfile and Docker Compose configuration.
- Add GitHub Actions to run the build and tests.
- Deploy a demo if a suitable host is available; keep secrets in environment variables.
- Write README, architecture diagram, ER diagram, setup instructions, API examples, test strategy, and known limitations.

**Exit criteria:** Another developer can clone the repository, start it using documented steps, run tests, inspect the API, and understand the major design decisions.

---

## 10. Testing strategy and proof of correctness

Tests are not decoration for a resume. They are evidence that the system's guarantees were checked.

| Area | Important test |
|---|---|
| Catalog | Validation, duplicate SKU, inactive product behaviour, pagination |
| Authentication | Invalid credentials, token expiry/invalid signature, password never returned |
| Authorization | Customer cannot perform admin operation or read another customer's order |
| Inventory | Negative stock rejected; reserved quantity cannot exceed on-hand quantity |
| Reservation | Insufficient stock does not create a pending reservation or alter inventory |
| Concurrency | Many simultaneous requests competing for limited stock never oversell |
| Confirmation | Inventory, reservation, and order commit or roll back together |
| Cancellation | Repeated cancel calls release stock at most once |
| Idempotency | Same key/same request replays; same key/different body conflicts; concurrent duplicate does not duplicate work |
| Expiry | Due pending reservation expires and stock is released once |
| Redis | Cache hit/miss/invalidation and rate-limit behaviour |

### Required headline concurrency scenario

Given:

```text
Product stock on hand: 1
Reserved quantity: 0
Available quantity: 1
Concurrent customers: 100
Requested quantity per customer: 1
```

Expected result:

```text
Successful reservations: exactly 1
Rejected for unavailable stock: 99
Final reserved quantity: 1
Final available quantity: 0
Oversold quantity: 0
```

The test must run against PostgreSQL using realistic concurrent transactions. A mocked repository test is not sufficient evidence for this guarantee.

### Performance claims

Do not write claims such as "handles 10,000 requests per second" without a reproducible benchmark. If measuring performance, record hardware/runtime, dataset, concurrency, test duration, and the exact endpoint. Compare before/after caching under the same conditions.

---

## 11. Git workflow and project hygiene

Use Git as part of the engineering work, not as a green-square exercise.

Suggested workflow:

- `main` remains in a working state.
- Create focused branches such as `feature/product-catalog`, `feature/reservation-service`, and `test/concurrent-reservation`.
- Use small commits with meaningful messages.
- Open pull requests for significant milestones, even when working alone, to practise review and document decisions.
- Keep `application-local` settings and secrets out of version control.
- Add `.env.example` with placeholder variable names, never real credentials.
- Record important trade-offs in short Architecture Decision Records (ADRs), for example `docs/adr/001-inventory-concurrency-strategy.md`.

---

## 12. Resume presentation — only after implementation

Possible project entry after the work is genuinely completed:

**StockGuard — Inventory Reservation & Order Management API**  
*Java, Spring Boot, PostgreSQL, Redis, Docker, JUnit, Testcontainers*

- Built a REST API for product inventory, time-bound reservations, and order management using Spring Boot and PostgreSQL.
- Implemented transaction-safe inventory reservations and verified overselling prevention with concurrent integration tests against PostgreSQL.
- Added idempotent reservation requests and automated expiry handling to make retries and reservation lifecycle transitions safe.
- Integrated Redis for catalog caching and shared API rate limiting, with PostgreSQL retained as the source of truth.

Only include each bullet once the feature exists and its behaviour has been tested. Replace generic descriptions with real measured results where appropriate. Do not claim production usage, user counts, throughput, or reliability that you have not measured.

---

## 13. How to learn while building — rules for the AI mentor

**Updated learner preference (2026-11-13):** Implement features directly in small, reviewable steps and explain the code alongside the work. Explain the purpose of every new file/folder, relevant annotations, request flow, and backend concepts when used. Do not block progress on quizzes or require a learner attempt before implementation. Run available checks directly and report actual results. This preference overrides conflicting teaching instructions below and in the historical kickoff prompt.

The AI assistant is a **teacher, reviewer, and debugging partner**, not an autopilot that writes the project for you.

For every new concept or phase, follow this loop:

1. Explain the problem in plain language before introducing Spring annotations or code.
2. Explain why the concept is needed in StockGuard and what would go wrong without it.
3. Show the relevant request/data flow and a small example, not the entire feature implementation.
4. Ask me to describe the design or attempt the implementation.
5. Review my code and point out the exact issue, why it is an issue, and how to reason about fixing it.
6. Give hints before providing a complete solution. Do not immediately replace my code with a finished implementation.
7. Once I understand it, help me write tests and verify the behaviour.
8. Summarize what I learned, the decisions we made, and the next task.

### Teaching requirements

- Use English for all file and folder names, source code, comments, and documentation. Use a Hinglish/English mixture only in conversation.
- Keep README.md and PROJECT_STATE.md concise: essential setup/status, important decisions, verified results, and the next small task only. Keep the detailed plan in this guide.
- Assume I know Java fundamentals, OOP, collections, Git, and basic SQL, but I am still learning Spring Boot and deeper backend engineering.
- Teach from first principles when a concept is new. Do not assume that knowing an annotation means understanding what Spring does behind it.
- Explain important Java, Spring, SQL, HTTP, security, and Redis concepts before using them.
- Use examples from StockGuard rather than unrelated tutorial examples.
- Explain alternatives and trade-offs, especially for transactions, locking, caching, idempotency, and security.
- Be explicit about which work belongs in the controller, service, repository, database, or Redis.
- Do not add a library or infrastructure component without explaining why it is needed.
- Do not introduce microservices, Kafka, Kubernetes, or advanced patterns just to make the project look complex.
- When showing code, explain the important lines and the execution flow.
- Prefer small, reviewable implementation steps. Do not generate dozens of files at once.
- Do not silently invent completed work. Ask me to run commands/tests and share the actual output when verification is needed.
- If uncertain about current Spring Boot, PostgreSQL, Redis, or library behaviour, consult official documentation and identify the version being discussed.
- Correct my misconceptions directly and respectfully. Do not agree with incorrect assumptions just to be encouraging.

### Definition of done for each task

A task is complete only when:

- I can explain what it does and why it exists.
- The implementation follows the agreed design.
- Relevant tests pass.
- Error and edge cases have been considered.
- The code is committed with a meaningful message.
- The project guide/progress notes are updated if an architectural decision changed.

---

## 14. Copy-and-use kickoff prompt for a new AI chat

Copy the prompt below into a fresh chat and attach this guide.

```text
You are my senior Java backend mentor for a portfolio project called StockGuard.
Read STOCKGUARD_PROJECT_GUIDE.md carefully and treat it as the project specification and learning plan.

My goal is not just to finish an application. I want to understand the backend engineering concepts well enough to design, implement, test, debug, and explain the project independently in interviews.

Teaching rules:
- Teach one small step at a time.
- Explain the concept and the reason before giving code.
- Do not generate the entire project or a complete feature in one response.
- Ask me to reason, design, or implement the next small piece.
- Review my attempt before showing a full solution.
- Give hints before complete answers unless I explicitly ask for the full solution.
- Explain the execution flow, database effects, and trade-offs.
- Make me write tests, especially integration and concurrency tests.
- Never claim something works unless we have run the relevant command or test and seen its result.
- Keep PostgreSQL as the durable source of truth. Redis is for carefully chosen supporting use cases, not inventory correctness.
- Prefer a modular monolith. Do not add unnecessary infrastructure or abstractions.

Start at Phase 0. First explain the StockGuard domain, the actors, the reservation lifecycle, and the inventory invariants in beginner-friendly language. Then ask me to explain the flow back to you before we move to database design.

At the end of each session, give me:
1. Concepts learned.
2. Decisions made.
3. What I implemented or verified (only what I actually showed you).
4. Tests/commands still to run.
5. The exact next small task.

Do not skip ahead because you can generate code quickly. My learning is as important as the finished project.
```

---

## 15. Initial progress tracker

This tracker records functional implementation and verification. Git initialization/commit, Redis/Docker and deployment are still pending; see PROJECT_STATE.md for delivery status.

- [ ] Phase 0 — Domain understanding and repository setup
- [ ] Phase 1 — Database and ER design
- [x] Phase 2 — Spring Boot foundation and catalog API
- [x] Phase 3 — Authentication and authorization
- [x] Phase 4 — Inventory and basic reservation workflow
- [x] Phase 5 — Concurrency correctness demonstrated with PostgreSQL tests
- [x] Phase 6 — Confirmation, cancellation, and orders
- [x] Phase 7 — Idempotency
- [x] Phase 8 — Reservation expiry
- [ ] Phase 9 — Redis caching and rate limiting
- [ ] Phase 10 — Quality, documentation, CI, and delivery

**Project status:** Core backend implemented and verified (43 tests plus packaged restart/expiry check). Redis, Docker and delivery remain pending; see PROJECT_STATE.md.  
**Current phase:** Core functionality through Phase 8 implemented; Redis/Docker deferred by user. Swagger and CI configuration prepared.  
**Next action:** Demonstrate the complete workflow in Swagger; then add Redis/Docker and finish delivery when requested.

---

## 16. Official documentation starting points

Use documentation that matches the versions selected for the project.

- Spring Boot: https://docs.spring.io/spring-boot/index.html
- Spring Framework — transaction management: https://docs.spring.io/spring-framework/reference/data-access/transaction.html
- Spring Security: https://docs.spring.io/spring-security/reference/index.html
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/index.html
- PostgreSQL documentation: https://www.postgresql.org/docs/
- PostgreSQL explicit locking: https://www.postgresql.org/docs/current/explicit-locking.html
- Redis documentation: https://redis.io/docs/latest/
- Flyway documentation: https://documentation.red-gate.com/flyway
- Testcontainers for Java: https://java.testcontainers.org/
- JUnit 5: https://junit.org/junit5/docs/current/user-guide/
- OpenAPI specification: https://spec.openapis.org/oas/latest.html

These links are starting points, not a substitute for checking the documentation version used by the application.






