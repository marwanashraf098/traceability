/**
 * FR-DEMO shared frontend constants. Kept in one module (not defined inline in
 * DemoLanding.tsx) because App.tsx, Login.tsx, and StationGate.tsx all need them
 * without importing from a page component.
 */

/** Mirrors DemoSeeder.DEMO_TENANT_ID (backend) — fixed, never regenerated. */
export const DEMO_TENANT_ID = '91c6027e-0b23-4c56-84a6-2a769315ed2d'

/** Set the moment a demo session starts; cleared on session loss or a real login.
 *  Drives the /demo?expired=1 vs /login redirect in App.tsx's RequireAuth/RootRoute. */
export const DEMO_SESSION_MARKER = 'traced_demo'

/** The demo access token itself, persisted so a hard refresh can rehydrate it —
 *  real logins never write this key (see App.tsx's useAuthRefresh). */
export const DEMO_ACCESS_TOKEN_KEY = 'traced_demo_token'
