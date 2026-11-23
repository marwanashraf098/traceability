import type { BazarDay } from '../../bazar/api'

/**
 * U1a — the day Close day opens when none is asked for: the OLDEST day that still needs closing (the server's
 * `needs_close`: open or changed after its close, with money — the same rule End event blocks on), else today, else
 * the last day. One rule for the computer page, the phone page and the event page's button label.
 */
export function defaultCloseDay(days: BazarDay[]): BazarDay | undefined {
  return days.find(d => d.needs_close === true) ?? days.find(d => d.is_today) ?? days[days.length - 1]
}
