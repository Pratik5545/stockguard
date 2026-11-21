# Reservation and order workflow

The [browser frontend](frontend.md) at `/` supports this workflow directly, including fresh action keys and safe explicit retries after uncertain responses.

## Try it in Swagger UI

1. Start the application with the README local-profile command; Flyway applies V3 automatically.
2. Open http://localhost:8080/swagger-ui/index.html.
3. Log in as ADMIN, copy accessToken, click **Authorize**, and paste the token (without the word Bearer).
4. Create a product with initialStock, or copy an existing product ID from the public catalog.
5. Register/log in as a separate CUSTOMER. Replace the authorized token with the customer token; ADMIN cannot create customer reservations.
6. Call POST /api/v1/reservations with a new Idempotency-Key and the body below.
7. Confirm or cancel using the returned reservation ID and a new key. GET endpoints show the current state and your order history.

Create body:

```json
{"productId":"replace-with-product-uuid","quantity":1}
```

Confirm/cancel have no request body. Login tokens last 15 minutes. Do not share tokens or passwords.

## API contract

| Method | Path | Access | Success |
|---|---|---|---|
| POST | /api/v1/reservations | CUSTOMER | 201 reservation + Location |
| GET | /api/v1/reservations/{id} | Owner or ADMIN | 200 reservation |
| GET | /api/v1/me/reservations?page=0&size=20 | Authenticated; own records | 200 page |
| POST | /api/v1/reservations/{id}/confirm | Owner only | 201 order + Location |
| POST | /api/v1/reservations/{id}/cancel | Owner only | 200 cancelled reservation |
| GET | /api/v1/orders/{id} | Owner or ADMIN | 200 order |
| GET | /api/v1/me/orders?page=0&size=20 | Authenticated; own records | 200 page |

Creation, confirmation and cancellation require Idempotency-Key. Invalid requests return 400, missing authentication 401, wrong role 403, missing/hidden resources 404, stock/limit/lifecycle/key conflicts 409. Even ADMIN cannot confirm/cancel another customer's reservation. Page sizes are 1-100.

## Limits and expiry

| Property | Default |
|---|---|
| stockguard.reservation.hold-duration | 5m (allowed 1 second to 15 minutes) |
| stockguard.reservation.max-quantity | 2 (allowed 1-100) |
| stockguard.reservation.max-pending | 3 (allowed 1-100) |
| stockguard.reservation.expiry-interval-ms | 5000 |
| stockguard.reservation.expiry-batch-size | 100 (allowed 1-1000) |
| stockguard.reservation.expiry-enabled | true |

Override these through Spring configuration/environment. Single product per reservation; there is no shopping cart. Overdue PENDING records count toward the user cap until released. Replays/reads never extend a deadline. A new reservation after cancellation is a new hold, subject to limits.

At now >= expiresAt, confirmation AND cancellation reject the action. Stock remains held until the worker commits EXPIRED. Scheduling delay is not a release-time guarantee: contention, backlog and downtime can extend it. A restart catches up from durable PENDING records. Workers use SKIP LOCKED and process one reservation per transaction, up to the batch limit.

The allocation policy does not promise FIFO or equal purchase chances. Per-account limits do not stop multiple accounts or coordinated customers. With Redis enabled, reservation creation allows 10 attempts per customer per 60-second window; HTTP protection can return 429/503 before an idempotency replay. See [Redis policies](redis.md).

## Idempotency

- Keys accept 1-128 ASCII letters/digits/dot/underscore/colon/hyphen. UUID strings are suitable.
- Scope is user + operation + key. Create hashes product ID and integer quantity; confirm/cancel hash the reservation ID.
- Same key + same request returns the original successful status/body/Location, with Idempotent-Replayed: true, even if lifecycle state has since changed. Use GET for current state.
- Same key + different request returns 409. Concurrent duplicates execute once.
- Business changes and the response record commit in the same PostgreSQL transaction. Failed requests roll back and do not consume the key.
- Successful records are retained indefinitely in this release; there is no cleanup that silently allows key reuse.
- Product creation/stock adjustments do not implement this retry contract.

## Orders and stock

Reservation snapshots SKU/name/unit price. Confirmation copies those immutable snapshots into one PLACED order; later product edits/deactivation do not change the agreed price. Reservation confirmation is an application state transition, not real payment verification.

| Action | On hand | Reserved |
|---|---|---|
| Reserve q | unchanged | +q |
| Confirm q | -q | -q |
| Cancel/expire q | unchanged | -q |

No direct-order shortcut or multi-item checkout is implemented. Those would require explicit new contracts.

## Testing

TEST_DB_* enables the complete PostgreSQL suite. Workflow tests use signed test tokens and an adjustable Clock; the separate authentication suite exercises actual registration/login. They check ownership, limits, stable replays, snapshots, rollback, expiry boundaries and simultaneous requests. Random schemas isolate test data.

Swagger is served locally from the packaged dependency. CI configuration is in .github/workflows/verify.yml and uses PostgreSQL 18 on the GitHub runner; it is not a local Docker setup. The workflow has not been run on GitHub yet.

Verified on 2026-11-20: 53 tests passed with zero failures/errors/skips, including 10 Redis cache/rate-limit/outage tests. The 100-customer scenarios produced exactly 1/7 accepted reservations for stock 1/7. A separate packaged-process restart test expired a persisted hold and replayed its idempotency record without reserving again. Observed expiry delay was about 14.17 seconds during deliberate downtime/restart with a 2-second test hold and 500ms polling interval; this is not a guaranteed release bound.
