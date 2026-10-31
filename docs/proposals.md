# Proposals and client portal

Studio now connects proposals to project delivery. This increment lives in the workspace service on port 8086; the gateway already routes its API. Keeping acceptance and project creation within one service/database makes the transition atomic. A future independent sales service would require an outbox and a recoverable project-creation workflow.

## Workflow

1. Add a client contact in Studio.
2. Open **Proposals**, then **New proposal**. Enter a title, brief, target date and up to 20 priced deliverables. Prices are positive GBP strings with exactly two decimals. The browser calculates totals using integer minor units.
3. Save the draft and review the proposal document. Drafts are private to the studio account.
4. **Publish to client portal** makes the proposal available to the intended client. It does not send email; share the application portal address separately.
5. The client signs in at `/portal`, using the verified email that matches the contact. They can post feedback and accept or decline after confirmation.
6. Acceptance creates a project linked to the original client and one starter task per deliverable. It records the decision and activity in the same transaction. Repeated or simultaneous matching acceptance creates one project and one set of tasks. Decline creates no project. A terminal decision cannot be reversed through this API.
7. The studio opens the linked project and updates task status. The client portal shows progress for the proposal’s linked deliverable tasks and the proposal conversation. Later internal project tasks are excluded from the portal. It exposes no unrelated projects or studio activity. Connected proposal lists refresh every 30 seconds.

All prices are quotes. Acceptance does not create an invoice or move money. There is no e-signature or contract execution feature. Scope and pricing are fixed after draft creation in this increment; revisions use a new proposal.

## Demo and connected accounts

Without sign-in, Studio and Client portal use the same explicitly labelled browser-session demo. The portal's **Preview as client** selector switches between sample clients and hides drafts. Creation, publishing, feedback, acceptance, project creation, task updates and progress all work in the demo. Demo changes are isolated from connected accounts.

For server-backed records, start Keycloak, PostgreSQL, workspace and gateway as described in `workspace.md`. Flyway applies `V2__proposals.sql` when the workspace service starts. An existing V1 database retains its data. No additional database or credentials are required.

For a fresh Keycloak realm, `realm.json` now installs explicit email/profile claim mappings. Keycloak skips realm imports when that realm already exists. To update an existing local realm without replacing users, run `python infra/compose/configure-portal-scopes.py` from the repository root while Keycloak is running. The script prompts for the local administrator password (or reads `KEYCLOAK_ADMIN_PASSWORD`) and idempotently installs/links the two scopes. Sign out and back in afterwards. The script has not been run against a persistent local realm in this increment.

Use two distinct Keycloak users for the studio and client. The studio contact email must match the client user's `email` claim, and the client must have `email_verified=true`. The frontend requests `openid profile email` and returns portal sign-ins to the portal. The studio owner cannot accept their own proposal. Unverified email claims are denied portal access, including direct API lookups. Drafts remain inaccessible to client accounts. Authorization is enforced by the backend rather than the demo selector.

The local Keycloak development realm does not currently have outbound email delivery configured. Use trusted test accounts with verified emails for local integration; hosted deployment needs an actual email-verification flow. Do not treat an unverified self-entered email as proof of client identity.

## Data and API

The workspace service owns `proposals`, `proposal_comments` and `proposal_deliverables`. Proposal payloads are stored as JSONB; recipient email is captured from the studio-owned client at creation. Owner-scoped foreign keys link the client and accepted project. Decision operations lock the proposal row to serialize acceptance, decline and retries. Feedback uses a client-generated UUID and rejects changed retries. Decision actor and timestamp are persisted.

- `GET/POST /api/v1/workspace/proposals`
- `GET /api/v1/workspace/proposals/{id}`
- `POST /api/v1/workspace/proposals/{id}/publish`
- `POST /api/v1/workspace/proposals/{id}/decision`
- `POST /api/v1/workspace/proposals/{id}/comments`
- `GET /api/v1/workspace/portal`

The OpenAPI contract is in `contracts/openapi/workspace.json`.

## Verification and remaining work

PostgreSQL tests cover private drafts, create retry conflicts, recipient ownership, verified email enforcement, studio self-acceptance rejection, concurrent acceptance, one project/task set, terminal decline, feedback deduplication and API amount validation. A real Keycloak container test verifies signed email and email_verified claims for both verified and unverified client users using the imported realm. Only the disposable test realm enables password grants; the product keeps PKCE and disables direct password grants. Frontend tests cover exact quote totals and acceptance/decline state transitions. Browser checks cover a new two-deliverable proposal, publishing, client feedback, acceptance, linked-project navigation, completed-task progress in the portal, persistence after refresh, and desktop/mobile layouts.

A full two-account PKCE browser end-to-end run remains pending. Draft editing, versioned revisions, expiry, invitations/email delivery, attachments, milestone-specific delivery approval, invoices and legal signatures are not included in this increment. The broader payment/refund, notification and reconciliation backlog remains in `status.md`.
