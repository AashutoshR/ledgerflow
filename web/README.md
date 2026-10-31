# LedgerFlow web

Run `npm ci` then `npm run dev` from this directory. Open http://localhost:5173 (the Keycloak redirect allowlist uses localhost). Vite proxies `/api` to the API gateway on port 8080. Run Keycloak, ledger, payments and gateway as described in the root documentation for live actions.

Unauthenticated visitors see an explicitly labeled design preview. No preview balances are treated as live money. Sign-in uses authorization code with PKCE. Tokens are held in session storage; a gateway deployment on the same origin is expected outside local development.

The customer interface supports wallet creation, balance/history, simulated funding, transfer confirmation and backend status polling. Pending submissions retain their idempotency key and request in session storage for recovery within that browser tab; starting another movement resumes the uncertain one. Clearing browser storage removes that recovery aid, so full cross-device pending-operation listing is still needed.

Motion uses transform/opacity and respects reduced-motion preferences. Desktop and phone layouts have been visually inspected. The full authenticated browser-to-service flow has not yet been exercised. Merchant/refund and administrator screens remain pending.

Checks: `npm run build`, `npm run typecheck`, `npm run test:ci`.

## Visual redesign - 30 October 2026

The root route is now a custom dark editorial homepage with an animated SVG globe, pointer-responsive orbit scene, workspace preview tabs, animated principle illustrations and FAQ disclosures. `/workspace` retains the customer wallet application in a matching blue and copper theme. Mobile layouts, reduced-motion support, a pause control for ambient CSS animation and keyboard tab navigation are included. Merchant and control panels are explicitly illustrative; no new backend capabilities are implied.

Verified: production build and existing money precision test; desktop homepage, mobile customer workspace, business preview switching and FAQ expansion in the browser. Authenticated end-to-end flows remain unverified in this redesign pass.

## Studio expansion

`/workspace` is now the client/project/task application. `/payments` is the existing wallet/transfer UI. `/callback` completes sign-in and enters Studio. The public homepage introduces both domains. See `../docs/workspace.md` for real service setup and demo storage behavior. Homepage and Studio are loaded as separate chunks.

Proposals and Client portal are now available in Studio. `/portal` opens the client view directly. See `../docs/proposals.md` for the demo workflow and verified-email access requirements.
