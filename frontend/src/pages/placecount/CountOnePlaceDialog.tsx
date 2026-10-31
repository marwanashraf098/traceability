import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Alert, Button, Input, Modal } from '../../components/ui'
import { startPlaceCount, ServerMessageError } from '../../api'

// Warehouse places S5 — "Count one place" (Stock take; Put away, every role): scan the shelf / box sticker, the count opens (or the
// place's open count is joined). A zone / rack sticker is refused by the server ("Scan a shelf or box"). The only
// text input on the screen while open.
export default function CountOnePlaceDialog({ from, onClose }: { from: 'stock-take' | 'put-away'; onClose: () => void }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function start() {
    const scan = value.replace(/\s+/g, '')
    if (!scan || busy) return
    setBusy(true)
    setError(null)
    try {
      const c = await startPlaceCount({ scan })
      navigate(`/place-counts/${c.id}?from=${from}`)
    } catch (e: unknown) {
      setError(e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('placeCount.error'))
      setValue('')
      setBusy(false)
    }
  }

  return (
    <Modal title={t('placeCount.oneTitle')} onClose={onClose}>
      <div className="space-y-4" data-testid="count-one-place">
        <p className="text-body text-muted">{t('placeCount.oneBody')}</p>
        <Input scan autoFocus value={value} placeholder={t('placeCount.onePlaceholder')} aria-label={t('placeCount.onePlaceholder')}
          onChange={e => setValue(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') start() }} />
        {error && <Alert tone="critical" title={error} />}
        <div className="flex justify-end gap-2.5">
          <Button variant="secondary" onClick={onClose}>{t('placeCount.cancel')}</Button>
          <Button loading={busy} onClick={start}>{t('placeCount.oneStart')}</Button>
        </div>
      </div>
    </Modal>
  )
}
