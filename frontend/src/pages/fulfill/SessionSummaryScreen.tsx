import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import { getPackSessionSummary, TransferCommandError, SessionSummary } from '../../api'
import { Alert, Button, Skeleton } from '../../components/ui'

// Pick & Pack S4 — shown after "End session" (design/pick-pack-waybill-mockup/Summary.html).
// Full-screen like the session it closes. The page keeps ?summary=<sessionId> in the URL so a
// reload lands here again (GET /pack-sessions/{id}/summary — the caller's own sessions only).

function duration(seconds: number, t: (k: string, o?: Record<string, unknown>) => string) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? t('fulfill.waybill.summary.durationHm', { h, m }) : t('fulfill.waybill.summary.durationM', { m })
}

export default function SessionSummaryScreen({ sessionId, onBack, onNewSession, starting, startError }: {
  sessionId: string
  onBack: () => void
  onNewSession: () => void
  starting: boolean
  /** Starting a new session failed (e.g. the store switched back to the order queue). */
  startError: string | null
}) {
  const { t, i18n } = useTranslation()
  const [summary, setSummary] = useState<SessionSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getPackSessionSummary(sessionId)
      .then(s => { if (!cancelled) setSummary(s) })
      .catch(e => {
        if (cancelled) return
        setError(e instanceof TransferCommandError
          ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
          : t('fulfill.waybill.session.error'))
      })
    return () => { cancelled = true }
  }, [sessionId, i18n.language, t])

  const fmt = (iso: string | null) => iso
    ? new Date(iso).toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' }) : '…'

  return (
    <div className="min-h-screen bg-base flex items-start justify-center p-4 md:p-10" data-testid="session-summary">
      <div className="w-full max-w-2xl space-y-5">
        {error && <Alert tone="critical" title={error} />}
        {!summary && !error && <Skeleton className="h-96 rounded-2xl" />}
        {summary && (
          <>
            <div className="card p-6 space-y-1">
              <p className="text-caption text-muted uppercase tracking-wide">{t('fulfill.waybill.summary.title')}</p>
              <p className="text-h2 text-primary">
                {summary.workerName ?? ''} · {fmt(summary.startedAt)} – {fmt(summary.endedAt)}
              </p>
              <p className="text-small text-muted">{duration(summary.durationSeconds, t)}</p>
            </div>

            <div className="grid grid-cols-3 gap-3" data-testid="summary-counts">
              <Count label={t('fulfill.waybill.summary.packed')} value={summary.packed} tone="text-success-text" />
              <Count label={t('fulfill.waybill.summary.setAside')} value={summary.setAside} tone="text-warning-text" />
              <Count label={t('fulfill.waybill.summary.rejected')} value={summary.rejected} tone="text-critical-text" />
            </div>

            <div className="card p-5 space-y-3" data-testid="summary-needs-manager">
              <p className="text-h4 text-primary">{t('fulfill.waybill.summary.needsManager')}</p>
              {summary.needsManager.length === 0 ? (
                <p className="flex items-center gap-2 text-small text-muted">
                  <CheckCircle2 size={16} className="text-success" /> {t('fulfill.waybill.summary.nothingForManager')}
                </p>
              ) : (
                <ul className="divide-y divide-line">
                  {summary.needsManager.map((n, i) => (
                    <li key={`${n.at}-${i}`} className="py-2.5 flex items-center justify-between gap-3 text-small">
                      <span className="min-w-0">
                        <span className="font-mono font-semibold text-primary">{n.orderNumber ?? n.rawScan ?? '—'}</span>
                        {n.customerName && <span className="text-muted"> · {n.customerName}</span>}
                      </span>
                      <span className={n.kind === 'set_aside'
                        ? 'text-caption font-semibold rounded-full px-2.5 py-1 bg-warning/[0.14] text-warning-text'
                        : 'text-caption font-semibold rounded-full px-2.5 py-1 bg-critical/[0.12] text-critical-text'}>
                        {n.kind === 'set_aside'
                          ? t('fulfill.waybill.lists.statusSetAside', {
                              reason: t(`fulfill.waybill.setAside.reasons.${n.reason && ['piece_missing', 'damaged_piece', 'waybill_damaged', 'other'].includes(n.reason) ? n.reason : 'other'}`) })
                          : t('fulfill.waybill.lists.statusCancelled')}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {summary.unscannedFromTodaysBatches > 0 && (
              <div className="flex items-center gap-2 rounded-xl border border-warning/30 bg-warning/[0.10] px-4 py-3 text-small text-primary"
                data-testid="summary-unscanned">
                <AlertTriangle size={16} className="text-warning flex-shrink-0" />
                {t('fulfill.waybill.summary.unscanned', { count: summary.unscannedFromTodaysBatches })}
              </div>
            )}

            {startError && <Alert tone="critical" title={startError} />}
            <div className="flex flex-wrap gap-3">
              <Button variant="secondary" className="flex-1" onClick={onBack}>{t('fulfill.waybill.summary.back')}</Button>
              <Button className="flex-1" loading={starting} onClick={onNewSession}>{t('fulfill.waybill.summary.newSession')}</Button>
            </div>
          </>
        )}
        {error && (
          <Button variant="secondary" className="w-full" onClick={onBack}>{t('fulfill.waybill.summary.back')}</Button>
        )}
      </div>
    </div>
  )
}

function Count({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="card p-4">
      <p className="text-small text-muted">{label}</p>
      <p className={`text-h2 font-mono ${tone}`}>{value}</p>
    </div>
  )
}
