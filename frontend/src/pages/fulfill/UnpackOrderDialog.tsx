import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'
import { PackedOrderRow, TransferCommandError, UnpackOrderResult, UnpackReason, unpackOrder } from '../../api'
import { Alert, Button, Modal, cn } from '../../components/ui'

// Unpack-order S4 — owner / manager confirm for POST /fulfill/{id}/unpack-order: a reason (default "Customer edited
// the order"; "Other" needs a note ≤ 200), the Bosta COD reminder when the server flags it, and the server's own
// EN / AR refusal (an open pickup session links to it). Nothing is decided here — the server checks every guard.

const REASONS: UnpackReason[] = ['customer_edited_order', 'packed_wrong_item', 'other']
const NOTE_MAX = 200

export function UnpackOrderDialog({ row, onClose, onUnpacked }: {
  row: PackedOrderRow
  onClose: () => void
  onUnpacked: (r: UnpackOrderResult) => void
}) {
  const { t, i18n } = useTranslation()
  const [reason, setReason] = useState<UnpackReason>('customer_edited_order')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<{ text: string; pickupSessionId: string | null } | null>(null)
  const number = row.orderNumber ?? row.orderId.slice(0, 8)
  const noteOk = reason !== 'other' || (note.trim().length > 0 && note.trim().length <= NOTE_MAX)
  const money = (n: number | null) => (n ?? 0).toLocaleString(i18n.language)

  async function confirm() {
    if (busy || !noteOk) return
    setBusy(true)
    setError(null)
    try {
      onUnpacked(await unpackOrder(row.orderId, reason, reason === 'other' ? note.trim() : undefined))
    } catch (e) {
      if (e instanceof TransferCommandError) {
        const session = e.details?.pickupSessionId
        setError({ text: i18n.language === 'ar' ? e.messageAr : e.messageEn,
          pickupSessionId: typeof session === 'string' ? session : null })
      } else {
        setError({ text: t('fulfill.unpack.failed'), pickupSessionId: null })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={t('fulfill.unpack.title', { number })} onClose={onClose}>
      <div className="space-y-4" data-testid="unpack-dialog">
        <p className="text-body text-primary">{t('fulfill.unpack.body', { count: row.pieces })}</p>
        <p className="text-small text-muted">
          {row.selfPickup ? t('fulfill.unpack.selfPickupNote') : t('fulfill.unpack.waybillNote')}
        </p>

        {row.codMismatch && (
          <p className="text-small text-warning-text bg-warning/[0.10] border border-warning/[0.25] rounded-lg px-3 py-2 flex items-start gap-2"
            data-testid="unpack-cod">
            <AlertTriangle size={14} strokeWidth={2} className="mt-0.5 flex-shrink-0" />
            <span>{t('fulfill.packed.cod', { bosta: money(row.bostaCod), order: money(row.orderCod) })}</span>
          </p>
        )}

        <fieldset className="space-y-2">
          <legend className="text-small font-semibold text-primary mb-1">{t('fulfill.unpack.reasonLabel')}</legend>
          {REASONS.map(r => (
            <label key={r}
              className={cn('flex items-center gap-2.5 rounded-lg border px-3 py-2 cursor-pointer transition-colors',
                reason === r ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
              <input type="radio" name="unpack-reason" value={r} checked={reason === r}
                onChange={() => setReason(r)} className="accent-trace-blue" />
              <span className="text-body text-primary">{t(`fulfill.unpack.reasons.${r}`)}</span>
            </label>
          ))}
          {reason === 'other' && (
            <div>
              <textarea value={note} maxLength={NOTE_MAX} rows={2} autoFocus
                onChange={e => setNote(e.target.value)}
                placeholder={t('fulfill.unpack.notePlaceholder')}
                aria-label={t('fulfill.unpack.notePlaceholder')}
                className="input w-full resize-none" data-testid="unpack-note" />
              <p className="text-caption text-muted text-end">{t('fulfill.unpack.noteCount', { count: note.length })}</p>
            </div>
          )}
        </fieldset>

        {error && (
          <div data-testid="unpack-error">
            <Alert tone="critical" title={error.text} />
            {error.pickupSessionId && (
              <Link to={`/pickups?session=${error.pickupSessionId}`} data-testid="unpack-open-pickup"
                className="inline-block mt-2 text-small font-semibold text-trace-blue hover:underline">
                {t('fulfill.unpack.openPickup')}
              </Link>
            )}
          </div>
        )}

        <div className="flex justify-end gap-3 pt-1">
          <Button variant="secondary" onClick={onClose}>{t('fulfill.unpack.cancel')}</Button>
          <Button loading={busy} disabled={!noteOk} onClick={confirm}>{t('fulfill.unpack.confirm')}</Button>
        </div>
      </div>
    </Modal>
  )
}
