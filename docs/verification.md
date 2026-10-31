# Local verification - 30 October 2026



Environment: Windows 11, Java 21, Docker Desktop Linux engine 28.3.2, WSL 3.0.3 and PostgreSQL 17.6-alpine through Testcontainers.



Command: Maven Wrapper `--batch-mode --no-transfer-progress verify`.



Passed: 2 money unit tests, 6 HTTP/authentication tests and 9 PostgreSQL integration tests. Integration coverage includes balanced/idempotent posting, changed-request conflicts, ownership, insufficient funds rollback, concurrent spending, simultaneous retries, signed/paginated history, frozen wallets and completed retries, freeze-before-post ordering, V1-to-V2 migration with retained data, immutable records and rejection/rollback of unbalanced journals.



The first real application-context run found a configuration/bean name collision in service security configuration. Filter-chain methods now have distinct names in all services. A commit-time constraint assertion was corrected to expect the transaction manager exception and check its database error plus rollback.



This does not establish full product readiness or performance targets. RabbitMQ delivery, browser workflows, load/failure recovery and the full Compose stack remain unverified.

## Transfer workflow increment

Full reactor verification also passes after adding transfer orchestration: 2 money unit tests, 8 wallet HTTP/authentication tests, 10 ledger PostgreSQL tests, 1 ledger HTTP-client test, 4 payments PostgreSQL/API tests and 1 real Keycloak import/service-token test (26 total).

New coverage checks stable ledger decisions after a lost response, rejection permanence after later funding, internal API scope/audience/client restrictions, concurrent persisted idempotency, owner-only transfer status, 202/Location responses, expired leases, terminal failures, one completion outbox event, and service-account scope/audience configuration against Keycloak 26.4.1. Transfers have their own database and credentials. Full browser/payment/refund flows and a complete cross-service end-to-end deployment are still pending.

