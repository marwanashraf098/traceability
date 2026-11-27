import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { BazarError, getBazarEvent, openBazarBatch } from '../../bazar/api'
import { Button, Spinner } from '../../components/ui'
import TransferScanOut, { type BazarScanOutTarget } from '../TransferScanOut'

/**
 * Top-up (V245) — /bazar/:id/add-pieces: "Add more pieces" on a live bazar (owner / manager). Opens the bazar's batch
 * (or the one already open) and shows the scan-out screen on it: scan, send by count, ✕ — a list until "Send these to
 * the bazar". Full-screen, like the first send's /bazar/:id/scan-out.
 */
export default function BazarAddPieces() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [target, setTarget] = useState<BazarScanOutTarget | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    Promise.all([getBazarEvent(id), openBazarBatch(id)])
      .then(([ev, batch]) => {
        if (!cancelled) setTarget({ eventId: id, transferId: ev.transfer_id, batchId: batch.id, onlineStock: ev.online_stock })
      })
      .catch(e => { if (!cancelled) setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed')) })
    return () => { cancelled = true }
  }, [id, t, i18n.language])

  if (error || !id) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body" role="alert">{error ?? t('bazar.loadFailed')}</p>
        <Button variant="secondary" onClick={() => navigate(id ? `/bazar/${id}` : '/bazar')}>{t('bazar.breadcrumb')}</Button>
      </div>
    )
  }
  if (!target) {
    return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  }
  return <TransferScanOut bazar={target} />
}
