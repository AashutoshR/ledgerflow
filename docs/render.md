# Render free-tier deployment

The root `render.yaml` deploys five free Docker web services and one free PostgreSQL instance in Frankfurt. No paid compute plan is selected.

| Service | Purpose |
| --- | --- |
| ledgerflow-aashutoshr | Gateway, React frontend and public runtime configuration |
| ledgerflow-workspace-aashutoshr | Clients, projects, tasks and proposals |
| ledgerflow-ledger-aashutoshr | Simulated wallets and authoritative financial records |
| ledgerflow-payments-aashutoshr | Durable transfer workflows and recovery |
| ledgerflow-auth-aashutoshr | Keycloak registration and OIDC login |
| ledgerflow-data-aashutoshr | Shared free PostgreSQL database |

## Deployment

1. Create a public GitHub repository containing this source.
2. In Render, create a Blueprint from the repository and select `render.yaml`.
3. Review the resources: every compute/database plan must say Free before deploying.
4. Wait for the database and services to become healthy. Render generates the Keycloak administrator password and payments service secret in its environment settings.
5. Verify the assigned public hostnames. The Blueprint includes namespaced hostnames; if Render assigns different ones, update AUTH_HOST, FRONTEND_HOST, WORKSPACE_HOST, LEDGER_HOST and PAYMENTS_HOST where applicable.
6. Open the gateway URL. `/config.js` supplies the public OIDC issuer and disables demo mode. Sign in or register, then save a client or project and verify that it remains after refresh.

The Keycloak realm uses the frontend origin for callback/logout URLs and imports the service client secret from the runtime environment. Realm import skips an existing realm, so changed origins or client credentials on an already-initialized database must also be updated in the Keycloak administration console. Do not delete the realm or database to apply ordinary configuration updates.

The authentication image also idempotently installs the browser subject mapper through the local Admin API after startup. This repairs older realm imports without replacing accounts. Basic subject, profile and email mappings are included in fresh imports.

## Data isolation and startup

Free Render permits only one active free PostgreSQL database per workspace. The hosted services therefore use separate schemas: workspace, ledger, payments and keycloak. Flyway creates the application schemas; the Keycloak bootstrap creates its schema before authentication starts. Local development retains separate application databases and credentials.

All hosted database connections use the private database host. External database access is disabled by the Blueprint's empty IP allowlist. The free deployment shares one database credential across these schemas. This is a portfolio hosting compromise, not strict production isolation.

Free web services cannot receive private-network requests. The gateway and payments workflow call the public HTTPS service endpoints instead. Each target validates tokens. Ledger posting additionally requires the payments service scope, audience and client identity. No secrets are sent to the frontend.

## Accounts and portal

Application users can register through Keycloak. Their workspace starts empty and is persisted in PostgreSQL. Financial funds are simulated; designated test funding is enabled in this portfolio deployment.

For client-portal access, the client account's verified email must match the proposal recipient. SMTP delivery is not configured. A trusted administrator must verify test identities until a real verification provider is connected. The administration username is ledgerflow-admin; its generated password remains in Render's secret environment settings. It must never be added to Git or published in the README.

## Free-tier limits

- Free services sleep after 15 idle minutes and can take about a minute to start again.
- The workspace shares 750 free instance hours monthly across all free web services, including existing applications.
- Free PostgreSQL expires 30 days after creation and has a fixed 1 GB capacity. Export data before expiry if you want to retain it. The free database is not a permanent free storage plan.
- Free services have constrained memory/CPU, no persistent filesystem and no private inbound networking.
- Build minutes and bandwidth count against included workspace usage. Do not enable paid overages or upgrade plans for this deployment.

These limits are documented in [Render's free-tier guide](https://render.com/docs/free). This deployment does not establish an uptime or performance guarantee.

## Troubleshooting

The disposable local deployment smoke stack can be started with `docker compose -f infra/render/compose.smoke.yaml up -d` after building its auth and workspace images. `python infra/render/smoke.py` verifies real signed identities, PostgreSQL writes, portal ownership, acceptance retries and deliverable progress. It only connects to the localhost test ports and uses disposable fixture credentials. It is never run against the hosted app.

- Login redirects to localhost: check `/config.js`, AUTH_HOST and the Keycloak client's registered callback/origin values.
- Invalid redirect URI: update the Keycloak ledgerflow-web client with the gateway's exact HTTPS `/callback` URL.
- Empty client portal: check the contact email, the client's email_verified claim and the proposal's published state.
- A service is waking up: wait for its cold start, then retry. Transfer recovery retains the original operation reference.
- Database errors after 30 days: inspect the database expiry status. Do not silently replace the database and discard user records.
- A build fails: inspect its logs, verify the selected SERVICE environment value and confirm the repository includes the Maven wrapper and frontend lockfile.
