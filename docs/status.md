# Implementation status

Foundation is in progress. Local Docker is running after enabling the Windows hypervisor and restarting. Ledger and payments have PostgreSQL integration coverage; see verification and transfer workflow documentation.

One GBP wallet per identity. Postings lock the actor/key, wallet rows in UUID order, then balance rows in UUID order. Successful retries return the original operation; changed requests conflict. Direct simulated funding rejections roll back. Internal transfer decisions persist both confirmed postings and terminal business rejections; payments persists processing and final business outcomes.

Database constraints enforce balanced journals at commit, immutable posted records, no later journal append and nonnegative customer balances. Treasury may be negative to offset simulated issuance. Treasury locking serializes funding; performance remains unmeasured. Outbox records commit with postings; publishing is pending. Future freeze changes must acquire the same wallet row lock as posting.

Wallet history, administrator realm role checks, freeze/unfreeze, immutable administrator audit records, structured authentication/authorization errors and a wallet OpenAPI contract are implemented. MVC tests cover authentication, authorization, role conversion, ownership-error responses, funding disabled by default and explicit freeze state validation. PostgreSQL tests additionally cover signed pagination, freeze/retry behavior, freeze/posting ordering and upgrading an existing V1 schema through V3.

Remaining foundation work: full browser authentication verification, public API audience validation, role mapping for remaining services, persistence for other services, gateway request IDs/rate limits/CORS, merchant/admin frontend, remaining OpenAPI/event contracts, Compose smoke tests.

Transfer orchestration, durable idempotency, owner-only status lookups, leased recovery and scoped internal ledger authentication are implemented. Next financial increment: merchant registration, merchant payments and concurrent partial refunds, outbox delivery, inbox deduplication, notifications, reconciliation and remaining administration.

Operational work remains: tracing/dashboards, browser/fault/load tests, security scans, container publishing and staging deployment. No performance, availability or recovery targets have been measured.

## Customer frontend

React/TypeScript/Vite with Tailwind, TanStack Query, OIDC PKCE and Motion is implemented in `web`. The visual design uses ivory surfaces, forest-green balance cards, serif editorial typography, animated orbital forms and staggered activity entrances. Motion respects reduced-motion preferences. Dashboard and activity layouts were inspected at desktop and 390px phone widths. Build, type checks and the exact-amount test pass; frontend CI is configured.

Live customer wallet/history/funding/transfer calls are wired, with confirmation, polling and session-scoped idempotent recovery. Unauthenticated preview figures are labeled illustrative. Full authenticated browser end-to-end verification, merchant/refund screens and administrator flows remain incomplete. See `web/README.md`.

## Expanded workspace - 31 October 2026

A seventh service, `workspace`, owns client contacts, projects, tasks and transactional activity in a separate PostgreSQL database. The gateway routes its authenticated API on port 8086. React Studio at `/workspace` provides record creation, client/project links, task status changes, project filtering, search, computed progress and an activity feed. Unauthenticated demo edits persist only in the browser session and are clearly labelled. `/payments` retains the existing financial UI.

The Studio design uses a deep-green navigation rail, warm ivory workspace, serif headings, pastel project collections and animated sculptural details. The dark public homepage now introduces work management alongside payments. See [workspace documentation](workspace.md) for setup, verification and remaining limitations.

## Proposals and client portal - 31 October 2026

Studio now supports priced proposal drafts, publication to a verified-email client portal, feedback, acceptance/decline, atomic accepted-project creation and starter tasks. Clients can follow linked project progress. Demo and connected APIs are implemented; see [proposal workflow](proposals.md) for access rules, verification and remaining limitations.
