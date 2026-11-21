# Docker and database tests

## What each file does

- `Dockerfile`: builds the application with the Maven Wrapper and JDK 25, then copies only the packaged JAR to a JRE 25 runtime running as a non-root user. The image build skips tests; run `verify` separately before delivery.
- `.dockerignore`: allows only build inputs into the Docker build context. Local secrets, Git history, dependency caches and test files are excluded.
- `compose.yaml`: starts the app, PostgreSQL 18 and Redis 8.2 on a private Compose network. PostgreSQL and Redis must become healthy before the app starts. The API binds to loopback port 8081 by default; Redis binds to loopback 6380 for local-profile development. PostgreSQL has no host port.
- `scripts/Initialize-Docker.ps1`: generates local database and JWT secrets in ignored `.env.docker`; rerunning it preserves existing credentials.
- `TestDatabase.java`: uses `TEST_DB_*` when provided, otherwise starts one temporary PostgreSQL 18 Testcontainers instance per test JVM. Each database test suite keeps its own random schema.

The container runtime and Maven test JVM use UTC. This keeps timestamps consistent across machines and avoids sending obsolete host timezone aliases such as `Asia/Calcutta` to PostgreSQL builds that do not include those aliases. User-facing timezone conversion remains a client concern.

An image is the application template. A container is a running instance of that image. A named volume stores database files independently of the container.

Redis features are enabled automatically in Compose. Its contents are disposable and reset on restart; PostgreSQL data remains in the named volume. See [Redis behavior and request limits](redis.md).

## Start locally from PowerShell

Start Docker Desktop and wait for the engine to run. From the project root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Initialize-Docker.ps1
docker compose --env-file .env.docker up --build --detach --wait
docker compose --env-file .env.docker ps
```

The execution-policy override applies only to that PowerShell process. The first build downloads images, Maven and dependencies. Later builds reuse Docker layers and the Maven build cache.

- Swagger: http://localhost:8081/swagger-ui/index.html
- Health: http://localhost:8081/actuator/health
- Logs: `docker compose --env-file .env.docker logs --tail 100 app`
- Stop: `docker compose --env-file .env.docker down`
- Start again: `docker compose --env-file .env.docker up --detach --wait`
- Rebuild after Java changes: repeat the start command with `--build`.

This is a separate database from the original local-profile database. Existing local users, products and admin roles are not copied. See [authentication](authentication.md) for registration and first-admin setup. Inside Compose, `postgres` resolves to the database container; `localhost` would refer to the app container itself.

For the first Docker admin, register your account through Swagger, then open the database console:

```powershell
docker compose --env-file .env.docker exec postgres psql -U stockguard -d stockguard
```

Run the following SQL with the registered email, then enter `\q` to exit. Log in again to receive a token containing the updated role.

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'your-admin@example.com';
```

PostgreSQL 18 data is mounted at `/var/lib/postgresql` using the named `postgres_data` volume, following the [official image layout](https://hub.docker.com/_/postgres). Normal `down` preserves this data. Adding `--volumes` deletes the Compose database permanently. Preserve `.env.docker`: replacing its database password does not change the password already stored in an initialized volume.

`.env.docker` is loaded by Compose only because `--env-file` is specified. The original Spring local-profile configuration is independent. Never commit or print the generated secrets or resolved Compose environment. These files are for local development; deployment requires separate secret management and hosting configuration.

## Automated tests

With Docker Desktop running:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' verify
```

No manual test database setup is required. [Testcontainers](https://java.testcontainers.org/modules/databases/postgres/) starts a temporary PostgreSQL container with a random mapped port and generated test credentials; its resource reaper removes the container after the test JVM exits. Tests never use the Compose database by default. Docker being unavailable is a test failure, rather than silently skipping database coverage.

Maven uses `JAVA_HOME`, which may differ from `java` on PATH. If Maven reports `release version 25 not supported`, set `JAVA_HOME` to your installed JDK 25 directory in that terminal.

For an externally managed dedicated test database, set `TEST_DB_URL`, `TEST_DB_USERNAME` and `TEST_DB_PASSWORD`. The role needs schema-creation permission; suites remove only their randomly generated schemas. Redis integration tests still require Docker and start a separate disposable Redis container. GitHub Actions uses the same Testcontainers path on its Ubuntu runner.
