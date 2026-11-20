import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { BazarError, getBazarEvent, type BazarEventDetail } from '../../bazar/api'
import { Spinner } from '../../components/ui'
import { SetupView } from './r3/SetupView'
import { LiveView } from './r3/LiveView'

/**
 * Bazar POS B6 — /bazar/:id. Draft → Get ready (r3/SetupView); live / ended → the event's tabs (r3/LiveView).
 * R3 restyle (design/bazar-v2 V2_Setup, V2_Event): the views live in r3/; Close the day and End event are their own
 * pages (/bazar/:id/close-day, /bazar/:id/end-event).
 */
export default function BazarEventPage() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [ev, setEv] = useState<BazarEventDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!id) return Promise.resolve()
    return getBazarEvent(id).then(e => { setEv(e); setError(null) }).catch(e => {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.loadFailed'))
    })
  }, [id, navigate, t, i18n.language])
  useEffect(() => { load() }, [load])

  if (error && !ev) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!ev) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>

  return (
    <div className="flex flex-col gap-5 max-w-[1120px]" data-testid="bazar-event">
      {ev.status === 'draft'
        ? <SetupView ev={ev} reload={load} onChanged={setEv} />
        : <LiveView ev={ev} reload={load} />}
    </div>
  )
}
