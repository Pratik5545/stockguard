# Free portfolio deployment

The frontend is packaged with the API and served at `/`; no additional service or environment variable is needed. See [frontend behavior and checks](frontend.md). Frontend deployment remains a user action.

The root `render.yaml` defines two Free Render services. The Blueprint passes Render's JSON Schema validation.

## Services

- Render Free Docker web service runs the existing image; Render Free Key Value supplies Redis-compatible storage. Both use Frankfurt and private datastore connectivity.
- Neon Free PostgreSQL stores users, inventory, reservations, orders and idempotency records. Select PostgreSQL 18 and AWS Frankfurt when available.
- `render.yaml` defines both Render services, health checks, a small connection pool, JVM heap budget and deployment after successful CI. Secrets are dashboard inputs; Render generates a separate Base64 JWT signing key.

## Setup

1. Create a [Neon Free account](https://neon.com/pricing) and a dedicated `stockguard` project. Keep the Free plan. In **Connect**, select the database and role, and turn **Connection pooling** off to display the direct hostname. This changes the connection string shown by Neon; the application has its own small JDBC connection pool.
2. Prepare these three values from the connection dialog. Do not copy the full PostgreSQL URI into `DB_URL`:

   | Render variable | Value |
   |---|---|
   | `DB_URL` | `jdbc:postgresql://YOUR_DIRECT_HOST/YOUR_DATABASE?sslmode=require&channelBinding=require` |
   | `DB_USERNAME` | The Neon role name |
   | `DB_PASSWORD` | The Neon role password |

   For example, a **fictional** Neon string `postgresql://demo_user:example_password@ep-example.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require` becomes:

   - `DB_URL`: `jdbc:postgresql://ep-example.eu-central-1.aws.neon.tech/neondb?sslmode=require&channelBinding=require`
   - `DB_USERNAME`: `demo_user`
   - `DB_PASSWORD`: `example_password`

   If Neon shows a Java URL with `?user=...&password=...`, remove those two query parameters from `DB_URL` and enter them in the separate username/password fields. Keep `sslmode=require&channelBinding=require`. See [Neon's Java connection guide](https://neon.com/docs/guides/java).
3. Push the deployment files to GitHub and wait for the Verify workflow to pass.
4. Sign in to [Render](https://dashboard.render.com), connect the GitHub repository, choose **New > Blueprint**, select `Pratik5545/stockguard` and `main`. It reads root `render.yaml`.
5. Supply the three database values in Render, review that **both services use Free**, and create the Blueprint. Do not select paid compute, disks or workspace upgrades. Keep optional paid usage disabled; review account usage limits before enabling any payment method.
6. Wait for the web service to become healthy. Flyway creates the schema automatically. Open its assigned HTTPS URL plus `/actuator/health` and `/swagger-ui/index.html`.
7. Register a demo customer. For an admin, follow [first-admin setup](authentication.md) using the Neon SQL editor for this deployed database. Keep admin credentials private.

Never put database passwords, connection URIs containing passwords, JWT keys or access tokens in chat, source files or screenshots. Local PostgreSQL data is not automatically copied to Neon.

## Demo limitations and verification

- Render Free sleeps after 15 minutes without traffic; waking takes about a minute. During sleep, expiry cannot run. After wake, the existing worker releases overdue holds; their deadlines do not extend.
- Free Key Value can lose cache/quota data on restart. It currently runs Valkey 8, whereas local tests use Redis 8.2; verify compatibility on the live deployment. Durable records remain in PostgreSQL.
- Forwarded headers remain untrusted. Login/register limits use the socket peer, so users behind the hosting proxy can share a quota. This is a conservative demo limitation, not verified per-client IP protection. Customer reservation limits still use authenticated user IDs. The OpenAPI server URL is relative, so Swagger uses the page's HTTPS origin.
- The expiry poll runs every five seconds while the app is awake, so Neon may remain active even with no user traffic. Monitor free database compute/storage and Render build/bandwidth quotas. Do not use synthetic keep-alive pings.
- Render Free PostgreSQL expires after 30 days; this setup uses Neon instead. Free hosting is for a portfolio demonstration, not an availability guarantee.
- After deployment, check health/Swagger, registration/login, admin product creation, reserve/confirm/order, fresh stock/cache changes, rate-limit response, and expiry plus replay after restart. Observe memory during startup and normal use.

Sources checked November 21, 2026: [Render free limits](https://render.com/docs/free), [Blueprint specification](https://render.com/docs/blueprint-spec), [Key Value](https://render.com/docs/key-value), [Neon plans](https://neon.com/pricing).
