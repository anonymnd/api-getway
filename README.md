# API Gateway

A learning project built with Java 21 and Spring Boot. One HTTP gateway routes requests to Catalog and Orders services, with JWT authentication, rate limiting, caching, retries, circuit breakers and load balancing.

The browser dashboard displays real request traces, service health and policy metrics. Its controls send actual HTTP requests; no separate frontend build is required.

## Install and run

Install **JDK 21+**, **Maven 3.6.3+** and Git, then clone and build:

```sh
git clone https://github.com/anonymnd/api-getway.git
cd api-getway
mvn verify
```

On Windows, start the demo from PowerShell in the project folder:

```powershell
.\demo\run.ps1 -SkipBuild
```

Open [http://127.0.0.1:18180/](http://127.0.0.1:18180/). Register an account, or use the locally generated `learner` credentials in `.runtime/demo-credentials.txt`.

The launcher starts the gateway and four independent demo service processes: Catalog on **9201/9202**, Orders on **9301/9302**. It generates secrets on your computer and stores credentials, the user database and logs in the ignored `.runtime/` directory. Keep that directory private; preserve it to retain accounts and signing keys.

```powershell
# Check running processes
.\demo\run.ps1 -Action status

# Stop one replica to observe failover; use start to restore it
.\demo\run.ps1 -Action stop -Port 9202
.\demo\run.ps1 -Action start -Port 9202

# Switch from Round Robin to Least Connections
.\demo\run.ps1 -Action restart-gateway -Strategy LEAST_CONNECTIONS -SkipBuild

# Stop the demo before rebuilding its JAR on Windows
.\demo\run.ps1 -Action stop
```

## How it works

- `/auth/register` and `/auth/login` manage users and issue 30-minute JWTs. Passwords are stored as BCrypt hashes; browser tokens stay in tab memory.
- `/api/catalog/*` routes to Catalog, while `/api/orders/*` routes to Orders. The matching prefix is stripped before forwarding.
- Per-user, per-service token buckets enforce rate limits. Eligible Catalog GET responses are cached briefly and isolated by user; mutations invalidate the service cache.
- GET/HEAD requests can retry once on another replica for transient failures. Mutations are never automatically retried.
- Health checks exclude unready replicas. Per-replica circuit breakers stop repeated failing attempts and admit a recovery trial after cooldown.
- Round Robin rotates eligible replicas. Least Connections selects the replica with the fewest active upstream requests.

Try Catalog twice to observe a cache hit, send a burst to see `429` rate limiting, or use the failing Orders scenario to observe retries and circuit breakers.

## Configuration and tests

Routes and policy limits live in `src/main/resources/application.yml`. For manual startup without the Windows launcher, provide `GW_JWT_SECRET` (Base64-encoded, at least 32 random bytes) and `GW_DB_PASSWORD` in the gateway process environment. `GW_DEMO_PASSWORD` optionally seeds the demo user. Start each demo service in a separate terminal with `java demo/Service.java <port>`, using ports 9201, 9202, 9301 and 9302; launch the gateway with `java -jar target/api-gateway-1.0.0.jar`.

Run `mvn verify` for automated tests covering authentication, HTTP boundaries, routing, quotas, caching, safe retries, timeouts and circuit state transitions. After Windows demo startup, `demo/verify-live.ps1` exercises real services and briefly stops/restores a Catalog replica; its report stays in `target/`.

This is a local, single-process learning gateway. User accounts persist in H2; quotas, cache, metrics and circuit states are in memory. It does not include distributed state, TLS termination, streaming, WebSockets or high availability. All demo listeners bind to loopback.
