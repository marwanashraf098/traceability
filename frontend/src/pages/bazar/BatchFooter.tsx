import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BazarError, discardBazarBatch, sendBazarBatchOnce, type BazarBatch, type BazarBatchSent, type OnlineStock }
  from '../../bazar/api'
import { useRetryingCommand } from '../../bazar/useRetryingCommand'
import { Button } from '../../components/ui'
import { Status } from './SendByCount'

/**
 * Top-up (V245) — the batch screen's footer: "Send these to the bazar (N)" (one retry-safe request: a lost answer is
 * asked again with the SAME request id) and "Discard this list" (a second tap confirms — nothing ever moved). A piece
 * that changed since it was listed → nothing is sent and the server's message names it.
 */
export function BatchFooter({ eventId, batch, onlineStock, onDone, onBack }: {
  eventId: string
  batch: BazarBatch
  onlineStock?: OnlineStock
  onDone: () => void
  onBack: () => void
}) {
  const { t, i18n } = useTranslation()
  const send = useRetryingCommand<BazarBatchSent>()
  const [discarding, setDiscarding] = useState<'idle' | 'confirm' | 'busy'>('idle')
  const [discardError, setDiscardError] = useState<string | null>(null)

  useEffect(() => {
    if (send.state.kind === 'done') onDone()
  }, [send.state]) // eslint-disable-line react-hooks/exhaustive-deps

  async function discard() {
    if (discarding === 'idle') { setDiscarding('confirm'); return }
    setDiscarding('busy')
    try {
      await discardBazarBatch(eventId, batch.id)
      onBack()
    } catch (e) {
      setDiscarding('idle')
      setDiscardError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed'))
    }
  }

  const n = batch.total
  return (
    <div className="bg-panel border-t border-line px-6 py-4 space-y-2" data-testid="batch-footer">
      <p className="text-small text-muted" data-testid="batch-online-note">
        {onlineStock === 'leave' ? t('bazar.topup.keepNote') : t('bazar.topup.removeNote', { count: n })}
      </p>
      <Status state={send.state} testId="batch-send" />
      {discardError && <p role="alert" className="text-small text-danger">{discardError}</p>}
      <div className="flex flex-col sm:flex-row gap-2">
        <Button className="w-full" disabled={n === 0 || send.busy || discarding === 'busy'}
                onClick={() => send.run((requestId, timeoutMs) => sendBazarBatchOnce(eventId, batch.id, requestId, timeoutMs))}>
          {t('bazar.topup.send', { count: n })}
        </Button>
        <Button variant={discarding === 'confirm' ? 'danger' : 'tertiary'} disabled={send.busy || discarding === 'busy'}
                onClick={discard}>
          {discarding === 'confirm' ? t('bazar.topup.discardConfirm') : t('bazar.topup.discard')}
        </Button>
      </div>
    </div>
  )
}
