# Transfer workflow

The payments service owns its PostgreSQL database and never reads ledger tables. `POST /api/v1/transfers` validates a GBP decimal-string amount and records an actor-scoped idempotent request. A pending request returns HTTP 202 and `Location: /api/v1/transfers/{id}`. Repeated completed requests return their stored result; changed requests return 409. Status reads enforce initiator ownership.

A scheduled worker claims rows using `FOR UPDATE SKIP LOCKED`, commits a 30-second lease and then calls the ledger outside the database transaction. It first queries the existing decision by the original transfer ID. When no decision exists, it posts using that same reference.

Ledger decisions, including business rejections, are immutable. A successful decision commits with financial entries, balance changes and the ledger outbox. A rejected decision commits without financial movement. Therefore later funding or unfreezing cannot turn a previously rejected transfer into a successful one. The payments service records its confirmed outcome and outbox event in one transaction.

If an HTTP response is lost after ledger commit, recovery queries that decision rather than creating a new reference. A crashed worker's lease expires automatically. Retries use capped exponential backoff with jitter and batches of at most ten. Unknown outcomes stay PROCESSING and keep retrying; they are never guessed to be failures. Alerting/manual recovery controls and measured recovery targets remain pending.

## Local setup

Add `PAYMENTS_DB_PASSWORD` to `infra/compose/.env`, then recreate the PostgreSQL container with `docker compose up -d postgres` from that directory. On a new volume the initializer creates both databases. For an existing volume, run this explicit, repeatable bootstrap:

```powershell
docker compose exec postgres sh /opt/bootstrap/payments-db.sh
```

It creates only a missing payments role/database; it does not change an existing role password. Flyway applies the payments schema when that service starts. Do not delete the PostgreSQL volume to add this database.

The realm template now includes a confidential `payments-service` client with service accounts enabled and a default `ledger.post` client scope. That scope adds audience `ledger-internal`. For an existing Keycloak realm, create those settings in its administrator console; startup import does not update an existing realm. Retrieve the client secret from the Credentials tab and configure `PAYMENTS_CLIENT_SECRET` outside source control.

Start the ledger as documented in the README, then start payments in another terminal:

```powershell
$env:PAYMENTS_DB_PASSWORD = '<local payments database password>'
$env:PAYMENTS_CLIENT_SECRET = '<Keycloak payments-service client secret>'
.\mvnw.cmd -pl services/payments spring-boot:run
```

With a customer bearer token and an `Idempotency-Key`, post `{"sourceWalletId":"<owned-wallet>","destinationWalletId":"<recipient-wallet>","amount":"2.50","currency":"GBP"}` to port 8083 at `/api/v1/transfers`, or through the gateway on 8080. Poll the returned Location until SUCCEEDED or FAILED. Only SUCCEEDED confirms movement.

Internal ledger GET/PUT endpoints at `/internal/v1/transfers/{reference}` require a valid issuer-signed token with scope `ledger.post`, audience `ledger-internal`, and `azp=payments-service`. The gateway has no route for internal endpoints. Customer tokens cannot call them. The service token is acquired with client credentials; tokens and secrets are not persisted in transfer records.

## Verification boundaries

Real PostgreSQL tests cover both services' persistence and recovery state transitions. Ledger tests prove repeated reference decisions post once and preserve rejections. Payments tests simulate a lost ledger response and worker crash. An HTTP stub test checks client-credential requests, bearer authentication, GET-before-PUT and recovery from a committed decision after a 503 response. A real Keycloak 26.4.1 container imports the source realm template and verifies its service-account scope, audience, issuer, signature through its published signing keys, and absence of administrator privileges. This is not yet a browser-to-Keycloak-to-both-services end-to-end test. Merchant payments and refunds remain pending.
