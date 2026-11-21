# Frontend

The responsive application is served at `/` by Spring Boot, using same-origin `/api/v1` requests. It ships inside the existing JAR/Docker image. No frontend build, CDN, CORS configuration, separate hosting, or payment integration is required.

## Files and behavior

- `src/main/resources/static/index.html`: catalog, authentication dialog, customer history, and admin forms.
- `assets/styles.css`: responsive layout, keyboard focus indicators, and mobile styles.
- `assets/app.js`: rendering, navigation, pagination, forms, refresh, and countdown. API content uses textContent, never HTML interpolation.
- `assets/api.js`: HTTP errors, bearer headers, and reservation retry identity.
- `scripts/frontend-tests.mjs`: dependency-free Node request/retry tests.

Catalog and customer history refresh every 15 seconds while the tab is visible. Selecting a page supersedes an older request for that view. Background updates preserve quantity inputs on unchanged catalog rows and keyboard focus on available hold actions. If a focused hold action disappears during a refresh, focus moves to its record heading or the view heading. PostgreSQL decides whether a reservation succeeds. The countdown uses the response Date header to reduce client-clock differences. At the deadline, actions disable while the worker releases stock. Defaults shown are five-minute holds and maximum quantity two; update the UI if deployment limits change.

All lists are paginated. Customers create single-product holds, confirm/cancel and view orders. Admins create products and adjust stock using a catalog selection or product UUID. Admins have no reservation controls. Prices use INR, as explicitly specified by the V1 database migration.

## Design system and installed skills

Root [DESIGN.md](../DESIGN.md) documents the inventory-ledger layout, Segoe UI typography, palette, spacing, responsive states and purpose of each major choice. Root [AGENTS.md](../AGENTS.md) routes frontend work to the project-local `.codex/skills/` instructions. The official `npx --yes anti-ai-slop` interactive installer v2.1.0 installed 16 skills for Codex in project scope and generated its session pointer. No Git command was used. The core and UI/layout/dashboard/forms/human/navigation/design-system/mobile-layout instructions were read during this session; subsequent sessions can discover the pointer automatically.

No UI runtime library was added: native forms, dialogs, CSS grid and DOM APIs cover the existing workflows without another build/runtime dependency. Backend contracts and deployment services are preserved. The twenty user preferences inform choices rather than categorical visual bans.

Inline validation preserves input, negative stock removal and cancellation request confirmation, route headings receive keyboard focus, and offline feedback identifies stale data. Native dialogs close with Escape. Light-theme contrast was measured using the installed skill's checker; details in [design verification](design-verification.md).

## Authentication and retries

JWTs stay in JavaScript memory: no localStorage, sessionStorage, cookies, URL parameters, or logging. Refresh, closure, sign-out and expiry end the frontend session. Logout discards the token locally; the backend has no revocation endpoint. Decoded roles control presentation only; Spring Security enforces permissions. Public requests omit bearer tokens. Password inputs clear after submission/dialog closure. Registration is followed by a separate sign-in.

Each new create/confirm/cancel action gets a fresh UUID Idempotency-Key. A network error, server timeout, 5xx or incomplete success response leaves the original key and request available through **Retry previous action**. A first-attempt 429 releases the action. A 429 while retrying an uncertain action keeps the key because the limiter runs before the database replay check. A verified success or a definitive business rejection releases the action. Refresh and sign-out clear its in-memory state. Inspect your holds before starting over in a new session.

An authenticated 401 ends only the session that sent the request. Admin writes with an unreadable or incomplete response show an uncertain result and keep the form values. Check the current product or stock before resubmitting an admin write.

Static resources have an explicit public GET allowlist; API role rules remain unchanged. A restrictive page CSP allows only same-origin assets/connections. No third-party scripts or analytics are loaded.

## Run and verify

Start the backend using the existing local profile or Docker instructions, then open `http://localhost:8080/` (Compose: `http://localhost:8081/`). Opening index.html from disk does not provide the API. The Dockerfile already packages these resources. After the user deploys, the frontend will appear at `https://stockguard-api-1son.onrender.com/`.

```powershell
node --check src/main/resources/static/assets/app.js
node --test scripts/frontend-tests.mjs
./mvnw.cmd --batch-mode --no-transfer-progress verify
```

Use JDK 25 and running Docker Desktop for the backend suite. On November 22, 2026, Maven ran 57 tests with disposable PostgreSQL 18 and Redis 8.2. All passed with zero skips. Nine Node tests passed. Maven packaged the updated JAR.

Browser checks now use temporary Playwright installed only under ignored `target/ui-qa/` and the installed Chrome. `node scripts/frontend-browser-tests.cjs` verifies responsive overflow at 320/390/768/1280px, registration, reserve/confirm/cancel/orders, expired action disabling, admin creation/positive adjustments, error recovery and Escape. Requests are intercepted with clearly labelled test fixtures; this does not prove live backend integration. Screenshots are written under ignored target/ and desktop/mobile results were inspected. No fixture records or tokens are sent to the live API.

The fixture browser suite also delays a catalog refresh while navigating to another page, inserts a hold beside a focused action, and checks recovery from an incomplete admin response.

A separate local Chrome run used the packaged JAR with disposable PostgreSQL and Redis. It exercised new and duplicate signup, invalid and valid login, product creation, a stock increase and a rejected decrease. The browser discarded one committed reservation response. Its first retry hit the real Redis limit, then its next retry received the saved result. After confirmation PostgreSQL held one reservation and one order. The JAR ran with `-Duser.timezone=UTC`, as in the Dockerfile.

Remaining browser checks include elapsed session expiry, manual offline/online interaction and the deployed Render ingress. The local browser tooling is optional.
