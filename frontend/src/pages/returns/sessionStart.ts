/**
 * Open-session header timestamp. A session opened today shows only the time; one opened
 * on an earlier day shows date + time, so a long-idle session can't pass for a fresh one.
 * Locale follows the UI language (EN/AR), not the browser default.
 */
export function formatSessionStart(
  iso: string,
  lang: string,
  now: Date = new Date(),
): { sameDay: boolean; text: string } {
  const opened = new Date(iso)
  const sameDay = opened.getFullYear() === now.getFullYear()
    && opened.getMonth() === now.getMonth()
    && opened.getDate() === now.getDate()
  const text = sameDay
    ? opened.toLocaleTimeString(lang, { hour: 'numeric', minute: '2-digit' })
    : opened.toLocaleString(lang, {
        day: 'numeric', month: 'short',
        ...(opened.getFullYear() !== now.getFullYear() ? { year: 'numeric' as const } : {}),
        hour: 'numeric', minute: '2-digit',
      })
  return { sameDay, text }
}
