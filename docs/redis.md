# Redis caching and request limits

PostgreSQL remains authoritative for inventory, reservations, orders and idempotency. Redis stores only disposable product metadata and request counters. Spring Boot manages the Spring Data Redis/Lettuce dependency versions; Compose and tests use `redis:8.2-alpine`.

## Product detail cache

For `GET /api/v1/products/{id}` with Redis enabled:

1. Query PostgreSQL for active status, `catalog_version` and current derived availability.
2. Read `stockguard:product:v1:{productId}:{catalogVersion}` from Redis.
3. On a hit, combine cached metadata with the live availability.
4. On a miss or Redis error, query metadata from PostgreSQL and try to cache it for 60 seconds.

Only id, SKU, name, description, price and catalog version enter the JSON cache. Product lists remain uncached. Every detail request still makes a small PostgreSQL query; a hit avoids fetching the larger metadata fields again. Tests verify the metadata query is skipped on a hit. No latency or throughput improvement is claimed without a benchmark.

Migration V4 adds `catalog_version`. JPA `@Version` increments it transactionally when an admin updates a product. Readers select the committed version in PostgreSQL, so old cache keys become unreachable after an update; TTL removes them later. A reader finishing an old cache fill after a write cannot overwrite the new version's entry. Cache fills run only after the calling database transaction commits, so a rolled-back write cannot populate a version that a future committed write may reuse. Rollback preserves the old version. Deactivated products return 404 before cache lookup. Direct SQL metadata changes must also increment `catalog_version`; normal product writes go through JPA.

Availability is never cached. It may change immediately after any read, and the two database reads on a cache miss do not promise one immutable response snapshot. Reservation acceptance always locks and checks PostgreSQL inside the existing workflow transaction.

## Request limits

| Endpoint | Identity | Default quota |
|---|---|---|
| POST login and register | Shared quota per client IP (socket peer by default, Render edge address on the Free Blueprint), hashed before Redis storage | 20 admitted attempts / 60 seconds |
| POST reservation creation | Authenticated CUSTOMER UUID | 10 admitted attempts / 60 seconds |

`redis/rate-limit.lua` performs the check, increment and initial TTL atomically. A fixed window begins with the first admitted request. Rejections do not extend the window or increment beyond the limit. Independent app instances share the counter when they share Redis and the namespace. Fixed windows can admit a burst across a window boundary; this is not a fairness or bot-prevention guarantee.

The filter runs after Spring Security authorization: unauthenticated or wrong-role reservation requests retain 401/403 and consume no reservation quota. Login/registration attempts and authorized reservation requests count even when later validation or business rules reject them. GET, confirmation, cancellation and admin stock updates are not throttled by this feature.

Exhausted quota returns RFC 9457 JSON with HTTP 429 and `Retry-After` in whole seconds. Clients must wait before retrying. Admitted reservation retries still use durable PostgreSQL idempotency; HTTP protection can return 429/503 before a replay. Those rejections do not create reservations or idempotency records and do not consume a new idempotency key.

Local and direct deployments use the socket IP. The Render Free Blueprint sets `TRUST_RENDER_EDGE_IP=true` for its public web service. In that mode the auth limiter accepts one literal address from `CF-Connecting-IP`, canonicalizes IPv4/IPv6 and falls back to the peer for a missing, invalid or repeated header. Clients sharing a public IP share a quota. Redis stores a pseudonymous hash of the address.

This setting relies on Render's public ingress overwriting that header and the Free web service accepting traffic only through that ingress. Keep the setting disabled on an origin that untrusted callers can reach directly or through a private network. The limiter never selects an address from `X-Forwarded-For`. [Render client IP guidance](https://render.com/articles/host-pocketbase-on-render) and [Free web service networking](https://render.com/docs/free).

## Redis failure policy

- Cache reads/writes fail open: public product details fall back to PostgreSQL. Malformed JSON is treated as a miss and replaced.
- Protected POST requests fail closed: login/register/reservation creation return 503 with `Retry-After: 1` if Redis cannot enforce the limit. Existing tokens can still read, confirm and cancel.
- Inventory transitions, durable idempotency and expiry never depend on Redis. No stock is released by a Redis TTL.
- Redis connection/command timeouts are configured to 500 ms. Fallback requests can incur multiple Redis attempts; this is not an end-to-end latency guarantee.
- When REDIS_ENABLED is true, Actuator health includes Redis. An outage therefore makes health DOWN even though catalog fallback and other independent endpoints remain available.
- Compose disables Redis persistence and uses a 128 MB `noeviction` memory policy. Cache/counter state resets on Redis restart. Exhausted memory causes writes to fail through the policies above rather than silently evicting rate counters.

## Configuration and local use

Compose enables Redis automatically; it is reachable at `redis:6379` inside the stack and `127.0.0.1:6380` on the host. This unauthenticated host binding is for local development. Deployments need a private network and appropriate Redis authentication/TLS configuration.

The original local Spring profile keeps Redis features disabled unless enabled explicitly:

```powershell
docker compose --env-file .env.docker up --detach --wait redis
$env:REDIS_ENABLED = 'true'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6380'
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dspring-boot.run.profiles=local' spring-boot:run
```

Properties: `stockguard.redis.enabled`, `cache-ttl` (1s-10m), `rate-window` (1s-1h), `auth-limit`, `reservation-limit` (1-10000), and `namespace`. Use separate namespaces across environments. `spring.data.redis.*` configures the Redis connection; REDIS_PASSWORD is supported without storing it in source control. `.env.example` remains a reference; Spring does not load it automatically.

Internal Micrometer counters track product cache hits/misses/errors and rate decisions by scope/result. They are not tagged by user/IP. Only Actuator health is exposed publicly; metrics stay internal for now.

## Files and verification

- `ProductCache`: JSON cache access and fallback counters.
- `ProductReadState` / `ProductMetadata`: separate live stock/version from cached fields.
- `RedisFeaturesProperties` / `RedisFeaturesConfig`: validated settings and security-filter registration.
- `RedisRateLimiter` / `rate-limit.lua`: shared atomic fixed-window decisions.
- `RateLimitFilter`: endpoint identity selection and HTTP 429/503 responses.
- `RedisIntegrationTests`: real Redis/PostgreSQL coverage of cache hits, live stock, updates/deactivation, stale fills, rollback, TTL/corrupt entries, quotas, retries, security ordering, 100 concurrent decisions across two limiter objects, Redis outage and recovery.

References: [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html), [Redis atomic counters and rate limiting](https://redis.io/docs/latest/commands/incr/), [official Redis image](https://hub.docker.com/_/redis).
