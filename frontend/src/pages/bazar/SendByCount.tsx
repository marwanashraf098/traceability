import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Loader2, Minus, Plus, X } from 'lucide-react'
import {
  BazarError, batchCountOnce, batchRemoveOnce, removeFromSendOnce, sendByCountOnce, type BazarCountResult,
} from '../../bazar/api'
import {
  getBazarSendStock, productSizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize,
} from '../../bazar/catalog'
import { sellErrorText } from '../../bazar/format'
import { useRetryingCommand, type CommandState } from '../../bazar/useRetryingCommand'
import { Button } from '../../components/ui'
import { PickSheet } from './phone/PickSheet'

// Bazar POS B7d-1 — on the bazar's scan-out screen only (TransferScanOut in bazar mode): send N of a size without
// scanning (stock with or without barcodes — Traced picks the pieces at the main warehouse, oldest first), and take
// pieces back off the send before Mark sent (a scanned piece by its ✕, or N of a size). No text field: the quantity
// is a − / + stepper, so the scan field keeps its focus rules untouched. Every call is retry-safe (one request id per
// action, re-sent on a lost answer).

const MAX = 500

export function Stepper({ value, max, onChange, testId }: {
  value: number
  max: number
  onChange: (n: number) => void
  testId: string
}) {
  const { t } = useTranslation()
  return (
    <div className="flex items-center gap-2" role="group" aria-label={t('bazar.count.quantity')}>
      <button type="button" onClick={() => onChange(Math.max(1, value - 1))} disabled={value <= 1}
        aria-label={t('bazar.count.less')} data-testid={`${testId}-less`}
        className="h-10 w-10 rounded-lg border border-line bg-panel flex items-center justify-center disabled:opacity-40">
        <Minus size={16} aria-hidden />
      </button>
      <span className="min-w-[3ch] text-center font-mono text-h3 text-primary tabular-nums" data-testid={`${testId}-qty`}
            aria-live="polite">{value}</span>
      <button type="button" onClick={() => onChange(Math.min(max, value + 1))} disabled={value >= max}
        aria-label={t('bazar.count.more')} data-testid={`${testId}-more`}
        className="h-10 w-10 rounded-lg border border-line bg-panel flex items-center justify-center disabled:opacity-40">
        <Plus size={16} aria-hidden />
      </button>
    </div>
  )
}

export function Status({ state, testId }: { state: CommandState<unknown>; testId: string }) {
  const { t, i18n } = useTranslation()
  if (state.kind === 'sending') {
    return <p role="status" className="text-small text-muted flex items-center gap-2" data-testid={`${testId}-sending`}>
      <Loader2 size={14} className="animate-spin" aria-hidden />{t('bazar.count.sending')}
    </p>
  }
  if (state.kind === 'checking') {
    return <p role="status" className="text-small text-warning-text" data-testid={`${testId}-checking`}>
      {t('bazar.count.checking')}
    </p>
  }
  if (state.kind === 'error') {
    const { title, detail } = sellErrorText(t, i18n.language, state.error)
    return <p role="alert" className="text-small text-danger" data-testid={`${testId}-error`}>
      {detail && detail !== title ? `${title} — ${detail}` : title}
    </p>
  }
  return null
}

/** "Send by count": product → size → how many, with Main's stock and online orders' committed demand.
 *  Top-up (V245): with {@code batchId} the same panel adds to that batch's LIST instead (nothing moves until the
 *  batch is sent) — "Add N", the server's "N added · M have labels — scan them" when fewer were listed. */
export function SendByCountPanel({ eventId, onSent, batchId }: { eventId: string; onSent: () => void; batchId?: string }) {
  const { t, i18n } = useTranslation()
  const [stock, setStock] = useState<BazarCatalog | null>(null)
  const [stockFailed, setStockFailed] = useState(false)
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<{ product: BazarCatalogProduct; size: BazarCatalogSize } | null>(null)
  const [qty, setQty] = useState(1)
  const [confirming, setConfirming] = useState(false)
  const [sent, setSent] = useState<number | null>(null)
  const [partial, setPartial] = useState<string | null>(null)
  const cmd = useRetryingCommand<BazarCountResult>()

  const reload = useCallback(() => getBazarSendStock(eventId)
    .then(s => { setStock(s); setStockFailed(false); return s })
    .catch(() => { setStockFailed(true); return null }), [eventId])
  useEffect(() => { reload() }, [reload])

  // The picked size's numbers, kept fresh from the latest stock read.
  const size = picked
    ? stock?.products.flatMap(p => p.sizes).find(s => s.variant_id === picked.size.variant_id) ?? null
    : null
  const atMain = size?.left ?? 0
  // U1c: a send by count takes pieces WITHOUT a label only — labelled ones are scanned.
  const unlabelled = Math.min(atMain, size?.left_unlabelled ?? atMain)
  const labelled = atMain - unlabelled
  const committed = size?.committed ?? 0
  const short = committed > 0 && atMain - qty < committed

  useEffect(() => {
    if (cmd.state.kind !== 'done') return
    const res = cmd.state.result
    setSent(res.count)
    setPartial(res.code === 'SENT_PARTIAL'
      ? ((i18n.language.startsWith('ar') ? res.message_ar : res.message_en) ?? res.message_en ?? null) : null)
    setPicked(null)
    setQty(1)
    setConfirming(false)
    reload()
    onSent()
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  function choose(product: BazarCatalogProduct, s: BazarCatalogSize) {
    setPicking(false)
    cmd.reset()
    setSent(null)
    setPartial(null)
    setPicked({ product, size: s })
    setQty(1)
    setConfirming(false)
  }

  function changeQty(n: number) {
    setQty(n)
    setConfirming(false)
  }

  function sendIt() {
    if (!picked || cmd.busy) return
    if (short && !confirming) { setConfirming(true); return }   // a second tap sends — the warning never blocks
    const variantId = picked.size.variant_id
    const n = qty
    cmd.run((requestId, timeoutMs) => batchId
      ? batchCountOnce(eventId, batchId, variantId, n, requestId, timeoutMs)
      : sendByCountOnce(eventId, variantId, n, requestId, timeoutMs))
  }

  return (
    <section className="card p-4 space-y-3" data-testid="send-by-count">
      <div>
        <h2 className="text-caption text-muted uppercase tracking-widest">{t('bazar.count.title')}</h2>
        <p className="text-small text-muted mt-1">{t('bazar.count.intro')}</p>
      </div>

      {!picked ? (
        <Button variant="secondary" onClick={() => setPicking(true)}>
          {t('bazar.count.chooseProduct')}
        </Button>
      ) : (
        <div className="space-y-3">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-body font-medium text-primary truncate" data-testid="send-by-count-size">
                {productSizeName(picked.product, picked.size)}
              </p>
              <p className="text-small text-muted" data-testid="send-by-count-numbers">
                {labelled > 0
                  ? t('bazar.u1c.count.split', { n: unlabelled, labelled })
                  : t('bazar.count.atMain', { count: atMain })}
                {committed > 0 && <> · {t('bazar.count.committed', { count: committed })}</>}
              </p>
            </div>
            <Button variant="tertiary" size="sm" onClick={() => setPicking(true)} disabled={cmd.busy}>
              {t('bazar.count.change')}
            </Button>
          </div>
          <Stepper value={qty} max={Math.max(1, Math.min(MAX, unlabelled))} onChange={changeQty} testId="send-by-count" />
          {unlabelled === 0 && labelled > 0 && (
            <p role="status" className="text-small text-warning-text" data-testid="send-by-count-all-labelled">
              {t('bazar.u1c.count.allLabelled')}
            </p>
          )}
          {short && (
            <p role="alert" className="text-small text-warning-text flex items-start gap-2" data-testid="send-by-count-warning">
              <AlertTriangle size={16} className="shrink-0 mt-0.5" aria-hidden />
              <span>{t('bazar.count.warning', { count: committed, left: Math.max(0, atMain - qty) })}</span>
            </p>
          )}
          <Button className="w-full" onClick={sendIt} disabled={cmd.busy || unlabelled === 0}>
            {batchId
              ? (confirming ? t('bazar.topup.count.addAnyway', { count: qty }) : t('bazar.topup.count.add', { count: qty }))
              : (confirming ? t('bazar.count.sendAnyway', { count: qty }) : t('bazar.count.send', { count: qty }))}
          </Button>
        </div>
      )}

      <Status state={cmd.state} testId="send-by-count" />
      {sent != null && cmd.state.kind === 'done' && (
        <p role="status" className="text-small text-success" data-testid="send-by-count-done">
          {batchId ? t('bazar.topup.count.added', { count: sent }) : t('bazar.count.sent', { count: sent })}
        </p>
      )}
      {/* U1c: fewer pieces without a label than asked — those were sent; the labelled ones are scanned. */}
      {sent != null && cmd.state.kind === 'done' && partial && (
        <p role="alert" className="rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 text-small font-semibold text-warning-text"
           data-testid="send-by-count-partial">
          {partial}
        </p>
      )}
      {stockFailed && <p role="alert" className="text-small text-danger">{t('bazar.count.loadFailed')}</p>}

      {picking && (
        <PickSheet catalog={stock} mode="send" onPick={choose} onClose={() => setPicking(false)} />
      )}
    </section>
  )
}

/** "Remove N" on a line of the send (newest added first). {@code batchId}: a line of a top-up batch's list. */
export function RemoveCount({ eventId, variantId, max, onRemoved, batchId }: {
  eventId: string
  variantId: string
  max: number
  onRemoved: () => void
  batchId?: string
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [qty, setQty] = useState(1)
  const [removed, setRemoved] = useState<{ count: number; codes: string[] } | null>(null)
  const cmd = useRetryingCommand<BazarCountResult>()

  useEffect(() => {
    if (cmd.state.kind !== 'done') return
    setOpen(false)
    setQty(1)
    // U1b: say which pieces came off — a labelled one by its code, so the right piece goes back on the shelf.
    setRemoved({ count: cmd.state.result.count, codes: cmd.state.result.labelled_codes ?? [] })
    onRemoved()
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!open) {
    return (
      <div className="flex flex-col items-end gap-1">
        <Button variant="tertiary" size="sm" onClick={() => { cmd.reset(); setRemoved(null); setOpen(true) }}>
          {t('bazar.count.remove')}
        </Button>
        {removed && (
          <p role="status" className="text-small text-primary text-end whitespace-normal max-w-[260px]" data-testid="remove-count-done">
            {removed.codes.length > 0
              ? <>{t('bazar.u1b.count.removedCodes', { count: removed.codes.length })}{' '}
                  <span dir="ltr" className="font-mono font-semibold">{removed.codes.join(' · ')}</span>
                  {' — '}{t('bazar.u1b.count.putBack', { count: removed.codes.length })}</>
              : t('bazar.u1b.count.removedUnlabelled', { count: removed.count })}
          </p>
        )}
      </div>
    )
  }
  return (
    <div className="flex flex-col items-end gap-2" data-testid="remove-count">
      <div className="flex items-center gap-2">
        <Stepper value={Math.min(qty, max)} max={Math.max(1, max)} onChange={setQty} testId="remove-count" />
        <Button size="sm" variant="secondary" disabled={cmd.busy}
          onClick={() => {
            const n = Math.min(qty, max)
            cmd.run((requestId, timeoutMs) => batchId
              ? batchRemoveOnce(eventId, batchId, { variantId, quantity: n }, requestId, timeoutMs)
              : removeFromSendOnce(eventId, { variantId, quantity: n }, requestId, timeoutMs))
          }}>
          {t('bazar.count.removeN', { count: Math.min(qty, max) })}
        </Button>
        <Button size="sm" variant="tertiary" disabled={cmd.busy} onClick={() => setOpen(false)}>
          {t('bazar.count.cancel')}
        </Button>
      </div>
      <Status state={cmd.state} testId="remove-count" />
    </div>
  )
}

/** The ✕ on a scanned row: take that piece back off the send (or off a top-up batch's list, {@code batchId}). */
export function RemoveScanButton({ eventId, code, onRemoved, onError, batchId }: {
  eventId: string
  code: string
  onRemoved: () => void
  onError: (e: BazarError) => void
  batchId?: string
}) {
  const { t } = useTranslation()
  const cmd = useRetryingCommand<BazarCountResult>()
  useEffect(() => {
    if (cmd.state.kind === 'done') onRemoved()
    if (cmd.state.kind === 'error') onError(cmd.state.error)
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <button type="button" disabled={cmd.busy} aria-label={t('bazar.count.removeScan')} data-testid="remove-scan"
      onClick={() => cmd.run((requestId, timeoutMs) => batchId
        ? batchRemoveOnce(eventId, batchId, { code }, requestId, timeoutMs)
        : removeFromSendOnce(eventId, { code }, requestId, timeoutMs))}
      className="h-8 w-8 rounded-md border border-line flex items-center justify-center text-muted hover:text-danger disabled:opacity-40">
      {cmd.busy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <X size={14} aria-hidden />}
    </button>
  )
}
