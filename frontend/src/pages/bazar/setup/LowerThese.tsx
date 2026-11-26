import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowDownToLine, Check, Loader2, Store } from 'lucide-react'
import {
  BazarError, downloadBazarLowerCsv, getBazarLowerList, getBazarSetup, markBazarLowered,
  type BazarLowerRow, type BazarSetupStatus,
} from '../../../bazar/api'
import { useCapabilities } from '../../../capabilities'
import { Modal, cn } from '../../../components/ui'
import { btnPrimary, btnSecondary } from '../r3/parts'

/**
 * Bazar B9b — "Lower these at the location you took them from". Traced added the bazar pieces to Traced Main in Shopify;
 * Traced never lowers another Shopify location, so the merchant lowers the same amounts there by hand. The list, its CSV
 * and "Done, I lowered them" are shared by the setup wizard, the bazar Overview's reminder card and Get ready's line.
 * Labelled and count-only pieces are listed alike (product, size, quantity).
 */

/**
 * The guided setup's status — only asked when GET /me says it is on (caps.bazarSetup); otherwise null and no request.
 * undefined = still loading.
 */
export function useBazarSetupStatus(): [BazarSetupStatus | null | undefined, (s: BazarSetupStatus | null) => void] {
  const caps = useCapabilities()
  const [status, setStatus] = useState<BazarSetupStatus | null | undefined>(undefined)
  useEffect(() => {
    if (!caps.bazarSetup) { setStatus(null); return }
    let cancelled = false
    getBazarSetup().then(s => { if (!cancelled) setStatus(s) }).catch(() => { if (!cancelled) setStatus(null) })
    return () => { cancelled = true }
  }, [caps.bazarSetup])
  return [status, setStatus]
}

/** The list: product, size, quantity; CSV; Done. {@code onDone} gets the new status once stamped. */
export function LowerTheseList({ onDone }: { onDone: (s: BazarSetupStatus) => void }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [rows, setRows] = useState<BazarLowerRow[] | null>(null)
  const [total, setTotal] = useState(0)
  const [lowered, setLowered] = useState(false)
  const [busy, setBusy] = useState<'csv' | 'done' | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getBazarLowerList()
      .then(l => { setRows(l.rows); setTotal(l.total); setLowered(l.lowered) })
      .catch(e => { setRows([]); setError(e instanceof BazarError ? e.message_(lang) : t('bazar.loadFailed')) })
  }, [lang, t])

  async function csv() {
    setBusy('csv'); setError(null)
    try { await downloadBazarLowerCsv(lang) } catch { setError(t('bazar.wizard.lower.csvFailed')) } finally { setBusy(null) }
  }

  async function done() {
    setBusy('done'); setError(null)
    try {
      const s = await markBazarLowered()
      setLowered(true)
      onDone(s)
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(lang) : t('bazar.saveFailed'))
    } finally {
      setBusy(null)
    }
  }

  if (rows === null) return <Loader2 size={18} className="animate-spin text-muted" aria-label={t('bazar.wizard.loading')} />
  return (
    <div className="flex flex-col gap-4" data-testid="bazar-lower-list">
      <div className="rounded-xl border border-line overflow-hidden">
        <table className="w-full text-body">
          <thead className="bg-charcoal text-small text-muted">
            <tr>
              <th scope="col" className="text-start font-semibold px-3 py-2">{t('bazar.wizard.lower.product')}</th>
              <th scope="col" className="text-start font-semibold px-3 py-2">{t('bazar.wizard.lower.size')}</th>
              <th scope="col" className="text-end font-semibold px-3 py-2">{t('bazar.wizard.lower.quantity')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.variantId} className="border-t border-line" data-testid="bazar-lower-row">
                <td className="px-3 py-2.5 text-primary font-medium">
                  {r.productTitle}
                  {r.sku && <span dir="ltr" className="block text-small text-muted font-normal">{r.sku}</span>}
                </td>
                <td className="px-3 py-2.5 text-primary">{r.variantTitle ?? t('bazar.wizard.oneSize')}</td>
                <td dir="ltr" className="px-3 py-2.5 text-end font-semibold tabular-nums text-primary">{r.quantity}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t border-line bg-charcoal">
              <td colSpan={2} className="px-3 py-2 font-semibold text-primary">{t('bazar.wizard.lower.total')}</td>
              <td dir="ltr" className="px-3 py-2 text-end font-bold tabular-nums text-primary" data-testid="bazar-lower-total">{total}</td>
            </tr>
          </tfoot>
        </table>
      </div>
      {error && <p role="alert" className="text-body text-critical">{error}</p>}
      <div className="flex flex-wrap items-center gap-2.5">
        <button type="button" className={btnSecondary} onClick={csv} disabled={busy !== null} data-testid="bazar-lower-csv">
          <ArrowDownToLine size={16} aria-hidden />{t('bazar.wizard.lower.csv')}
        </button>
        {lowered ? (
          <span className="inline-flex items-center gap-1.5 text-body font-semibold text-success-text" data-testid="bazar-lower-lowered">
            <Check size={16} aria-hidden />{t('bazar.wizard.lower.lowered')}
          </span>
        ) : (
          <button type="button" className={btnPrimary} onClick={done} disabled={busy !== null} data-testid="bazar-lower-done">
            {busy === 'done' && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {t('bazar.wizard.lower.done')}
          </button>
        )}
      </div>
    </div>
  )
}

/** The reminder until Done: a card on the bazar Overview (owner / manager). Also "continue setting up" for the owner. */
export function BazarSetupReminderCard() {
  const { t } = useTranslation()
  const [status, setStatus] = useBazarSetupStatus()
  const [open, setOpen] = useState(false)
  if (!status) return null
  const b = status.batch
  if (!b) {
    // Wizard entry: suggested only to a store that came for bazars; others use "Set up from new stock".
    if (!status.canSetUp || status.suggestStart === false) return null
    return (
      <div className="card p-4 flex items-center gap-3 flex-wrap" data-testid="bazar-setup-start-card">
        <span className="flex flex-col min-w-0 me-auto">
          <span className="text-body font-semibold text-primary">{t('bazar.wizard.startCard.title')}</span>
          <span className="text-small text-muted">{t('bazar.wizard.startCard.body')}</span>
        </span>
        <Link to="/bazar/setup" className={btnPrimary} data-testid="bazar-setup-start">{t('bazar.wizard.startCard.cta')}</Link>
      </div>
    )
  }
  if (b.lowered) return null
  return (
    <div className="rounded-2xl border border-warning/40 bg-warning/10 p-4 flex items-center gap-3 flex-wrap" data-testid="bazar-lower-reminder">
      <span aria-hidden className="w-9 h-9 rounded-lg bg-surface flex items-center justify-center text-warning-text shrink-0"><Store size={18} /></span>
      <span className="flex flex-col min-w-0 me-auto">
        <span className="text-body font-semibold text-primary">{t('bazar.wizard.reminder.title', { count: b.pieces })}</span>
        <span className="text-small text-muted">{t('bazar.wizard.reminder.body')}</span>
      </span>
      <button type="button" className={btnSecondary} onClick={() => setOpen(true)} data-testid="bazar-lower-view">
        {t('bazar.wizard.reminder.view')}
      </button>
      {open && (
        <Modal title={t('bazar.wizard.lower.title')} onClose={() => setOpen(false)}>
          <div className="flex flex-col gap-3">
            <p className="text-body text-muted">{t('bazar.wizard.lower.body')}</p>
            <LowerTheseList onDone={s => { setStatus(s); setOpen(false) }} />
          </div>
        </Modal>
      )}
    </div>
  )
}

/** Get ready's checklist line for the bazar the batch was received for — gone after Done. */
export function BazarLowerChecklistLine({ eventId }: { eventId: string }) {
  const { t } = useTranslation()
  const [status, setStatus] = useBazarSetupStatus()
  const [open, setOpen] = useState(false)
  const b = status?.batch
  if (!b || b.lowered || b.eventId !== eventId) return null
  return (
    <div className={cn('flex items-center gap-3 rounded-[10px] border border-warning/40 bg-warning/10 px-3 py-2.5 flex-wrap')}
         data-testid="bazar-lower-line">
      <span aria-hidden className="w-[22px] h-[22px] rounded border border-grey-100 bg-surface shrink-0" />
      <span className="flex flex-col min-w-0 me-auto">
        <span className="text-body font-semibold text-primary">{t('bazar.wizard.line.title')}</span>
        <span className="text-small text-muted">{t('bazar.wizard.line.sub', { count: b.pieces })}</span>
      </span>
      <button type="button" className="text-body font-semibold text-trace-blue" onClick={() => setOpen(true)}
              data-testid="bazar-lower-line-view">
        {t('bazar.wizard.reminder.view')}
      </button>
      {open && (
        <Modal title={t('bazar.wizard.lower.title')} onClose={() => setOpen(false)}>
          <div className="flex flex-col gap-3">
            <p className="text-body text-muted">{t('bazar.wizard.lower.body')}</p>
            <LowerTheseList onDone={s => { setStatus(s); setOpen(false) }} />
          </div>
        </Modal>
      )}
    </div>
  )
}
