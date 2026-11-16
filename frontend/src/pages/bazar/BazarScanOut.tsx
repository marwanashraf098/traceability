import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { BazarError, getBazarEvent } from '../../bazar/api'
import { Button, Spinner } from '../../components/ui'
import TransferScanOut from '../TransferScanOut'

/** Bazar POS B6 — /bazar/:id/scan-out: the transfer scan-out screen on the event's linked transfer (full-screen). */
export default function BazarScanOut() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [transferId, setTransferId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getBazarEvent(id).then(e => setTransferId(e.transfer_id)).catch(e =>
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed')))
  }, [id, t, i18n.language])

  if (error || !id) {
    return (
      <div className="flex flex-col items-center justify-center h-screen bg-base gap-4 px-6 text-center">
        <p className="text-danger text-body">{error ?? t('bazar.loadFailed')}</p>
        <Button variant="secondary" onClick={() => navigate('/bazar')}>{t('bazar.breadcrumb')}</Button>
      </div>
    )
  }
  if (!transferId) {
    return <div className="flex items-center justify-center h-screen bg-base"><Spinner size={32} /></div>
  }
  return <TransferScanOut bazar={{ eventId: id, transferId }} />
}
