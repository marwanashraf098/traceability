# Week 5 deferrals

Running list of good ideas and follow-ups intentionally deferred past the current
pilot-build scope (per `docs/four-week-pilot-core.md`: "No mid-build feature additions:
everything new goes to `week5.md`, even good ideas.").

- `FulfillService.scan()`'s transition-first-catch pattern (calls `InventoryLedger.transition()`
  then catches `StateConflictException` to return `ALREADY_RESERVED`) may hit
  `UnexpectedRollbackException` under real concurrency — Spring marks a participating
  transaction rollback-only when a nested `@Transactional` call throws, even if the caller
  catches it. Reproduced concretely against this exact code path (FR-22.3 investigation,
  2026-08-03). Diagnose-only follow-up — do not fix inside FR-22.
