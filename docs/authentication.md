# Authentication

## Contract

- `POST /api/v1/auth/register`: email and password; returns 201 with id/email/role. All registrations are CUSTOMER. Duplicate normalized email returns 409.
- `POST /api/v1/auth/login`: email and password; returns accessToken, tokenType (Bearer), expiresIn (900 seconds). Incorrect credentials return the same 401 message for unknown email and wrong password.
- Email is lowercased; passwords are not trimmed. Registration passwords must be 12-128 characters. Passwords use salted PBKDF2-HMAC-SHA256 (310,000 iterations); neither passwords nor hashes appear in response DTOs.
- Send `Authorization: Bearer <accessToken>` for protected requests. Missing/invalid tokens return 401; CUSTOMER access to `/api/v1/admin/**` returns 403.
- Product GET endpoints and health remain public. Requests carrying invalid bearer tokens are rejected even on public endpoints.

## Local walkthrough (PowerShell)

Start the application using the README local-profile command. In a second terminal:

```powershell
$credential = Get-Credential -Message 'Choose an email and a password of at least 12 characters'
$body = @{ email = $credential.UserName; password = $credential.GetNetworkCredential().Password } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/auth/register' -ContentType 'application/json' -Body $body
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/auth/login' -ContentType 'application/json' -Body $body
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
```

To create the first admin, register your account, then run this deliberate operator action in pgAdmin against the application database (replace the email):

```sql
UPDATE users SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP
WHERE email = 'your-email@example.com';
```

Log in again after promotion to obtain an ADMIN token. Pass `-Headers $headers` to the existing product POST/PATCH examples, after rebuilding headers from the new login response. There is no public role-assignment endpoint or default admin account.

## Signing key and boundaries

- The existing machine has a generated signing key in ignored `config/application-local.properties`. Keep it private and stable across restarts.
- Other machines must set JWT_SECRET to Base64-encoded random bytes (at least 32). There is no default production key. For example, generate it in PowerShell without printing it:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes); $env:JWT_SECRET = [Convert]::ToBase64String($bytes) } finally { $rng.Dispose() }
```

- HS256 tokens are signed, not encrypted. They contain user ID, role, issuer, audience and timestamps, with no password/email. The API verifies signature, algorithm, issuer, audience, expiry and required claims. Clocks must be synchronized (zero configured clock skew).
- No refresh tokens, logout/revocation, password reset or email verification yet. Role changes take effect in newly issued tokens; existing role claims remain valid until expiry. Rotating the signing key invalidates existing tokens.
- Authentication is stateless and header-based; session/form/basic authentication are disabled. CSRF is disabled because this API does not authenticate through cookies. Use HTTPS when deployed.
- Login/registration rate limiting is still pending in the Redis phase. Reservation/order ownership tests will be added when those endpoints exist.
