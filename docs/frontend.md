# Frontend

The responsive application is served at `/` by Spring Boot, using same-origin `/api/v1` requests. It ships inside the existing JAR/Docker image. No frontend build, CDN, CORS configuration, separate hosting, or payment integration is required.

## Files and behavior

- `src/main/resources/static/index.html`: catalog, authentication dialog, customer history, and admin forms.
- `assets/styles.css`: responsive layout, keyboard focus indicators, and mobile styles.
- `assets/app.js`: rendering, navigation, pagination, forms, refresh, and countdown. API content uses textContent, never HTML interpolation.
- `assets/api.js`: HTTP errors, bearer headers, and reservation retry identity.
- `scripts/frontend-tests.mjs`: dependency-free Node request/retry tests.

Catalog and customer history refresh every 15 seconds while the tab is visible. Background catalog updates preserve quantity input. Availability is informational; PostgreSQL decides whether reservations succeed. The countdown uses the response Date header to reduce client-clock differences. At the deadline, actions disable while the worker releases stock. Defaults shown are five-minute holds and maximum quantity two; update the UI if deployment limits change.

All lists are paginated. Customers create single-product holds, confirm/cancel and view orders. Admins create products and adjust stock using a catalog selection or product UUID. Admins have no reservation controls. The API has no currency field, so amounts display without an invented currency symbol.

## Authentication and retries

JWTs stay in JavaScript memory: no localStorage, sessionStorage, cookies, URL parameters, or logging. Refresh, closure, sign-out and expiry end the frontend session. Logout discards the token locally; the backend has no revocation endpoint. Decoded roles control presentation only; Spring Security enforces permissions. Public requests omit bearer tokens. Password inputs clear after submission/dialog closure. Registration is followed by a separate sign-in.

Each new create/confirm/cancel action gets a fresh UUID Idempotency-Key. Network/server errors preserve the action and key in memory. The explicit Retry button resends the exact request; other reservation mutations are blocked until it resolves. Success and definite 4xx responses clear the action. Refresh/sign-out loses retry state: inspect history before creating another hold. Admin writes are never automatically retried; check catalog/API after uncertain results before resubmitting.

Static resources have an explicit public GET allowlist; API role rules remain unchanged. A restrictive page CSP allows only same-origin assets/connections. No third-party scripts or analytics are loaded.

## Run and verify

Start the backend using the existing local profile or Docker instructions, then open `http://localhost:8080/` (Compose: `http://localhost:8081/`). Opening index.html from disk does not provide the API. The Dockerfile already packages these resources. After the user deploys, the frontend will appear at `https://stockguard-api-1son.onrender.com/`.

```powershell
node --check src/main/resources/static/assets/app.js
node --test scripts/frontend-tests.mjs
./mvnw.cmd --batch-mode --no-transfer-progress verify
```

Use JDK 25 and unpause Docker Desktop for the full suite. Verified November 22, 2026: five Node tests and two database-free Spring HTTP tests passed; Maven packaged the application. The installed Maven 3.10.0 was used because the local wrapper launcher failed. Full PostgreSQL/Redis suite was not rerun because Docker Desktop was paused.

Manual browser acceptance: desktop/mobile catalog and pagination; empty/error states; registration and incorrect login; reserve/confirm/order and separate cancel; deadline expiry; admin create/positive and negative stock adjustments; refresh/sign-out/session expiry; offline retry without duplicate holds. Keyboard-test navigation, dialog, fields and actions. Browser automation was unavailable; visual/end-to-end checks remain pending.
