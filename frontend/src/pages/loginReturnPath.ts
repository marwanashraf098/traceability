/**
 * Review mode S7 (fix A): where RequireAuth sent us from (state.from). Only an in-app path —
 * one leading slash, never "//host" or a full URL, never back to /login itself.
 */
export function returnPath(state: unknown): string | null {
  const from = (state as { from?: unknown } | null)?.from
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) return null
  if (from === '/login' || from.startsWith('/login?')) return null
  return from
}
