import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowRight, TriangleAlert } from 'lucide-react'
import { cn } from '../../components/ui'

/**
 * U1a — an ended bazar whose transfer isn't closed yet still has work: pieces still out (scan back / mark lost), or
 * just "Close the transfer". The report and the bazar list point to End event (/bazar/:id/end-event) until then.
 */
export function needsFinish(status: string, transferStatus: string | undefined): boolean {
  return status === 'ended' && transferStatus != null && transferStatus !== 'closed'
}

function finishText(t: (k: string, o?: Record<string, unknown>) => string, piecesOut: number | undefined): string {
  return piecesOut != null && piecesOut > 0
    ? t('bazar.u1a.stillOut', { count: piecesOut })
    : t('bazar.u1a.closeTransferToFinish')
}

/** The report's callout: what's left + a primary "Finish the event". */
export function FinishEventCallout({ eventId, piecesOut }: { eventId: string; piecesOut: number }) {
  const { t } = useTranslation()
  return (
    <section role="status" data-testid="report-finish"
      className="rounded-xl bg-warning/15 border border-warning/30 px-4 py-3 flex items-center gap-3 flex-wrap">
      <TriangleAlert size={20} aria-hidden className="text-warning-text shrink-0" />
      <span className="text-body font-semibold text-primary me-auto">{finishText(t, piecesOut)}</span>
      <Link to={`/bazar/${eventId}/end-event`} data-testid="report-finish-link"
        className="min-h-[44px] px-4 rounded-[10px] bg-trace-blue hover:bg-trace-blue-hover text-white font-semibold inline-flex items-center gap-1.5">
        {t('bazar.u1a.finishEvent')}<ArrowRight size={16} aria-hidden className="rtl:-scale-x-100" />
      </Link>
    </section>
  )
}

/** The list row / hero line: what's left, linking to End event. {@code dark} on the dark heroes. */
export function FinishEventLink({ eventId, piecesOut, dark }: { eventId: string; piecesOut: number | undefined; dark?: boolean }) {
  const { t } = useTranslation()
  return (
    <Link to={`/bazar/${eventId}/end-event`} data-testid={`bazar-finish-${eventId}`}
      className={cn('relative z-10 w-fit text-small font-semibold inline-flex items-center gap-1 hover:underline',
        dark ? 'text-[#FCD34D]' : 'text-warning-text')}>
      <TriangleAlert size={13} aria-hidden />
      {finishText(t, piecesOut)} · {t('bazar.u1a.finishEvent')}
      <ArrowRight size={13} aria-hidden className="rtl:-scale-x-100" />
    </Link>
  )
}
