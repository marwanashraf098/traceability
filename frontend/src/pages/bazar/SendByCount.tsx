import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Loader2, Minus, Plus, X } from 'lucide-react'
import { BazarError, removeFromSendOnce, sendByCountOnce } from '../../bazar/api'
import {
  getBazarSendStock, sizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize,
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

/** "Send by count": product → size → how many, with Main's stock and online orders' committed demand. */
export function SendByCountPanel({ eventId, onSent }: { eventId: string; onSent: () => void }) {
  const { t } = useTranslation()
  const [stock, setStock] = useState<BazarCatalog | null>(null)
  const [stockFailed, setStockFailed] = useState(false)
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<{ product: BazarCatalogProduct; size: BazarCatalogSize } | null>(null)
  const [qty, setQty] = useState(1)
  const [confirming, setConfirming] = useState(false)
  const [sent, setSent] = useState<number | null>(null)
  const cmd = useRetryingCommand<{ count: number }>()

  const reload = useCallback(() => getBazarSendStock(eventId)
    .then(s => { setStock(s); setStockFailed(false); return s })
    .catch(() => { setStockFailed(true); return null }), [eventId])
  useEffect(() => { reload() }, [reload])

  // The picked size's numbers, kept fresh from the latest stock read.
  const size = picked
    ? stock?.products.flatMap(p => p.sizes).find(s => s.variant_id === picked.size.variant_id) ?? null
    : null
  const atMain = size?.left ?? 0
  const committed = size?.committed ?? 0
  const short = committed > 0 && atMain - qty < committed

  useEffect(() => {
    if (cmd.state.kind !== 'done') return
    setSent(cmd.state.result.count)
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
    cmd.run((requestId, timeoutMs) => sendByCountOnce(eventId, variantId, n, requestId, timeoutMs))
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
                {picked.product.title} · {sizeName(picked.product, picked.size)}
              </p>
              <p className="text-small text-muted" data-testid="send-by-count-numbers">
                {t('bazar.count.atMain', { count: atMain })}
                {committed > 0 && <> · {t('bazar.count.committed', { count: committed })}</>}
              </p>
            </div>
            <Button variant="tertiary" size="sm" onClick={() => setPicking(true)} disabled={cmd.busy}>
              {t('bazar.count.change')}
            </Button>
          </div>
          <Stepper value={qty} max={Math.max(1, Math.min(MAX, atMain))} onChange={changeQty} testId="send-by-count" />
          {short && (
            <p role="alert" className="text-small text-warning-text flex items-start gap-2" data-testid="send-by-count-warning">
              <AlertTriangle size={16} className="shrink-0 mt-0.5" aria-hidden />
              <span>{t('bazar.count.warning', { count: committed, left: Math.max(0, atMain - qty) })}</span>
            </p>
          )}
          <Button className="w-full" onClick={sendIt} disabled={cmd.busy || atMain === 0}>
            {confirming ? t('bazar.count.sendAnyway', { count: qty }) : t('bazar.count.send', { count: qty })}
          </Button>
        </div>
      )}

      <Status state={cmd.state} testId="send-by-count" />
      {sent != null && cmd.state.kind === 'done' && (
        <p role="status" className="text-small text-success" data-testid="send-by-count-done">
          {t('bazar.count.sent', { count: sent })}
        </p>
      )}
      {stockFailed && <p role="alert" className="text-small text-danger">{t('bazar.count.loadFailed')}</p>}

      {picking && (
        <PickSheet catalog={stock} mode="send" onPick={choose} onClose={() => setPicking(false)} />
      )}
    </section>
  )
}

/** "Remove N" on a line of the send (newest added first). */
export function RemoveCount({ eventId, variantId, max, onRemoved }: {
  eventId: string
  variantId: string
  max: number
  onRemoved: () => void
}) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const [qty, setQty] = useState(1)
  const cmd = useRetryingCommand<{ count: number }>()

  useEffect(() => {
    if (cmd.state.kind !== 'done') return
    setOpen(false)
    setQty(1)
    onRemoved()
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!open) {
    return (
      <Button variant="tertiary" size="sm" onClick={() => { cmd.reset(); setOpen(true) }}>
        {t('bazar.count.remove')}
      </Button>
    )
  }
  return (
    <div className="flex flex-col items-end gap-2" data-testid="remove-count">
      <div className="flex items-center gap-2">
        <Stepper value={Math.min(qty, max)} max={Math.max(1, max)} onChange={setQty} testId="remove-count" />
        <Button size="sm" variant="secondary" disabled={cmd.busy}
          onClick={() => {
            const n = Math.min(qty, max)
            cmd.run((requestId, timeoutMs) => removeFromSendOnce(eventId, { variantId, quantity: n }, requestId, timeoutMs))
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

/** The ✕ on a scanned row: take that piece back off the send. */
export function RemoveScanButton({ eventId, code, onRemoved, onError }: {
  eventId: string
  code: string
  onRemoved: () => void
  onError: (e: BazarError) => void
}) {
  const { t } = useTranslation()
  const cmd = useRetryingCommand<{ count: number }>()
  useEffect(() => {
    if (cmd.state.kind === 'done') onRemoved()
    if (cmd.state.kind === 'error') onError(cmd.state.error)
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <button type="button" disabled={cmd.busy} aria-label={t('bazar.count.removeScan')} data-testid="remove-scan"
      onClick={() => cmd.run((requestId, timeoutMs) => removeFromSendOnce(eventId, { code }, requestId, timeoutMs))}
      className="h-8 w-8 rounded-md border border-line flex items-center justify-center text-muted hover:text-danger disabled:opacity-40">
      {cmd.busy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <X size={14} aria-hidden />}
    </button>
  )
}
