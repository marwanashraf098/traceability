import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Home, Loader2 } from 'lucide-react'
import {
  bringBackByCountOnce, bringHomeDamagedOnce, type BazarBringBackResult, type BazarLeftoverPiece,
  type BazarLeftovers, type BazarScanBackResult,
} from '../../bazar/api'
import {
  getBazarBringBackStock, sizeName, type BazarCatalog, type BazarCatalogProduct, type BazarCatalogSize,
} from '../../bazar/catalog'
import { useRetryingCommand } from '../../bazar/useRetryingCommand'
import { cn } from '../../components/ui'
import { PickSheet } from './phone/PickSheet'
import { Status, Stepper } from './SendByCount'

// Bazar POS B7d-2 — in the leftovers panel (phone /end and the computer's /end-event): bring leftovers WITHOUT a
// barcode back good by count (product → size → − / +; Traced picks the pieces still out, oldest first — labelled ones
// are still scanned), and bring a damaged one without a barcode home by tapping its row. Retry-safe (one request id
// per action). No text field: the scan field's focus rules are untouched.

const MAX = 500
const action = 'h-11 w-full rounded-xl text-body font-semibold flex items-center justify-center gap-2 disabled:opacity-60'

export function BringBackByCountPanel({ eventId, leftovers, onDone }: {
  eventId: string
  /** Re-read the untagged stock whenever the leftovers change. */
  leftovers: BazarLeftovers
  onDone: (l: BazarLeftovers) => void
}) {
  const { t } = useTranslation()
  const [stock, setStock] = useState<BazarCatalog | null>(null)
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<{ product: BazarCatalogProduct; size: BazarCatalogSize } | null>(null)
  const [qty, setQty] = useState(1)
  const [back, setBack] = useState<number | null>(null)
  const cmd = useRetryingCommand<BazarBringBackResult>()

  const reload = useCallback(() => getBazarBringBackStock(eventId).then(setStock).catch(() => { /* keep the last */ }),
    [eventId])
  useEffect(() => { reload() }, [reload, leftovers])

  const out = picked
    ? stock?.products.flatMap(p => p.sizes).find(s => s.variant_id === picked.size.variant_id)?.left ?? 0
    : 0

  useEffect(() => {
    if (cmd.state.kind !== 'done') return
    setBack(cmd.state.result.count)
    setPicked(null)
    setQty(1)
    onDone(cmd.state.result.leftovers)
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps

  function bring() {
    if (!picked || cmd.busy) return
    const variantId = picked.size.variant_id
    const n = qty
    cmd.run((requestId, timeoutMs) => bringBackByCountOnce(eventId, variantId, n, requestId, timeoutMs))
  }

  return (
    <section className="card p-4 flex flex-col gap-3" data-testid="bring-back-by-count">
      <div>
        <h3 className="text-body-lg font-semibold text-primary">{t('bazar.bringBack.title')}</h3>
        <p className="text-small text-muted">{t('bazar.bringBack.intro')}</p>
      </div>
      {!picked ? (
        <button type="button" className={cn(action, 'border border-line bg-surface text-primary')}
          onClick={() => { cmd.reset(); setBack(null); setPicking(true) }} data-testid="bring-back-choose">
          {t('bazar.bringBack.choose')}
        </button>
      ) : (
        <div className="flex flex-col gap-3">
          <div className="flex items-start justify-between gap-3">
            <span className="flex flex-col min-w-0">
              <span className="font-semibold text-primary truncate" data-testid="bring-back-size">
                {picked.product.title} · {sizeName(picked.product, picked.size)}
              </span>
              <span className="text-small text-muted" data-testid="bring-back-out">{t('bazar.bringBack.out', { count: out })}</span>
            </span>
            <button type="button" className="min-h-[40px] px-2 text-small font-semibold text-trace-blue shrink-0"
              disabled={cmd.busy} onClick={() => setPicking(true)}>{t('bazar.count.change')}</button>
          </div>
          <Stepper value={Math.min(qty, Math.max(1, out))} max={Math.max(1, Math.min(MAX, out))} onChange={setQty}
                   testId="bring-back" />
          <button type="button" className={cn(action, 'bg-[#16A34A] hover:bg-[#15803D] text-white')}
            disabled={cmd.busy || out === 0} onClick={bring} data-testid="bring-back-go">
            {cmd.busy && <Loader2 size={16} className="animate-spin" aria-hidden />}
            {t('bazar.bringBack.bring', { count: Math.min(qty, Math.max(1, out)) })}
          </button>
        </div>
      )}
      <Status state={cmd.state} testId="bring-back" />
      {back != null && cmd.state.kind === 'done' && (
        <p role="status" className="text-small font-semibold text-success-text" data-testid="bring-back-done">
          {t('bazar.bringBack.done', { count: back })}
        </p>
      )}
      {picking && <PickSheet catalog={stock} mode="back" onClose={() => setPicking(false)}
        onPick={(product, size) => { setPicking(false); setPicked({ product, size }); setQty(1) }} />}
    </section>
  )
}

/** "Bring home" on a damaged leftover WITHOUT a barcode (one with a barcode is scanned). */
export function BringHomeButton({ eventId, piece, onDone }: {
  eventId: string
  piece: BazarLeftoverPiece
  onDone: (r: BazarScanBackResult) => void
}) {
  const { t } = useTranslation()
  const cmd = useRetryingCommand<BazarScanBackResult>()
  useEffect(() => {
    if (cmd.state.kind === 'done') onDone(cmd.state.result)
  }, [cmd.state]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <span className="flex flex-col items-end gap-1">
      <button type="button" disabled={cmd.busy} data-testid={`bring-home-${piece.piece_id}`}
        onClick={() => cmd.run((requestId, timeoutMs) => bringHomeDamagedOnce(eventId, piece.piece_id, requestId, timeoutMs))}
        className="h-9 px-3 rounded-lg text-small font-semibold whitespace-nowrap border border-line bg-surface text-primary flex items-center gap-1.5 disabled:opacity-60">
        {cmd.busy ? <Loader2 size={14} className="animate-spin" aria-hidden /> : <Home size={14} aria-hidden />}
        {t('bazar.bringBack.bringHome')}
      </button>
      <Status state={cmd.state} testId="bring-home" />
    </span>
  )
}
