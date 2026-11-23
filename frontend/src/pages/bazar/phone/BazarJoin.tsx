import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { BazarError, getBazarSellerMe, setBazarSellerPin, type BazarSellerMe } from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { Button, Input, Spinner } from '../../../components/ui'
import { PhoneBrandRow, PhoneFrame, restOfTracedPath } from './shared'

/**
 * Bazar POS B6b — where an opened seller invite lands (/bazar/join/:id; the link already signed them in). No PIN yet
 * → "Pick a 4-digit PIN" (set once); then the event's Sell screen. Can't sell (workers not allowed, removed, not
 * live) → says so plainly.
 */
export default function BazarJoin() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [me, setMe] = useState<BazarSellerMe | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!id) return
    getBazarSellerMe(id).then(m => {
      setMe(m)
      if (!m.needs_pin && m.can_sell) {
        resetCapabilitiesCache()
        navigate(`/bazar/${encodeURIComponent(m.event_id)}/sell`, { replace: true })
      }
    }).catch(e => setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.join.loadFailed')))
  }, [id, navigate, i18n.language, t])
  useEffect(() => { load() }, [load])

  const header = (
    <>
      <PhoneBrandRow />
      {me && <h1 className="text-h4 text-sidebar-active">{me.event_name}</h1>}
    </>
  )

  if (error) {
    return (
      <PhoneFrame testId="bazar-join" header={header}>
        <p role="alert" className="text-body text-critical">{error}</p>
        <Button variant="secondary" onClick={() => navigate(restOfTracedPath())}>{t('bazar.phone.restOfTraced')}</Button>
      </PhoneFrame>
    )
  }
  if (!me) return <div className="min-h-[100dvh] bg-base flex items-center justify-center"><Spinner size={32} /></div>

  if (me.needs_pin) {
    return (
      <PhoneFrame testId="bazar-join" header={header}>
        <PinPicker name={me.name} onSet={load} />
      </PhoneFrame>
    )
  }

  const why = me.removed ? 'removed' : me.event_status === 'ended' ? 'ended'
    : me.event_status !== 'live' ? 'notLive' : 'notAllowed'
  return (
    <PhoneFrame testId="bazar-join" header={header}>
      <div className="flex flex-col gap-3 py-6 text-center items-center" data-testid={`join-${why}`}>
        <p className="text-h4 text-primary">{t(`bazar.join.${why}Title`)}</p>
        <p className="text-body text-muted max-w-xs">{t(`bazar.join.${why}Body`)}</p>
        {why !== 'removed' && why !== 'ended' && (
          <Button variant="secondary" onClick={load}>{t('bazar.join.checkAgain')}</Button>
        )}
      </div>
    </PhoneFrame>
  )
}

function PinPicker({ name, onSet }: { name: string; onSet: () => void }) {
  const { t, i18n } = useTranslation()
  const [pin, setPin] = useState('')
  const [again, setAgain] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const digits = (v: string) => v.replace(/[^0-9٠-٩]/g, '').replace(/[٠-٩]/g, d => String('٠١٢٣٤٥٦٧٨٩'.indexOf(d))).slice(0, 4)

  async function submit() {
    if (pin.length !== 4) { setError(t('bazar.join.pinFour')); return }
    if (pin !== again) { setError(t('bazar.join.pinMismatch')); return }
    setBusy(true)
    try {
      await setBazarSellerPin(pin)
      onSet()
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.join.pinFailed'))
    } finally { setBusy(false) }
  }

  return (
    <form className="flex flex-col gap-3" data-testid="bazar-pin-picker" onSubmit={e => { e.preventDefault(); submit() }}>
      <p className="text-h4 text-primary">{t('bazar.join.welcome', { name })}</p>
      <p className="text-body text-muted">{t('bazar.join.pinIntro')}</p>
      <label className="flex flex-col gap-1 text-small text-muted">
        {t('bazar.join.pin')}
        <Input value={pin} onChange={e => setPin(digits(e.target.value))} inputMode="numeric" type="password"
               autoComplete="new-password" dir="ltr" maxLength={4} className="text-center tracking-[0.6em] text-h4"
               data-testid="pin-new" autoFocus />
      </label>
      <label className="flex flex-col gap-1 text-small text-muted">
        {t('bazar.join.pinAgain')}
        <Input value={again} onChange={e => setAgain(digits(e.target.value))} inputMode="numeric" type="password"
               autoComplete="new-password" dir="ltr" maxLength={4} className="text-center tracking-[0.6em] text-h4"
               data-testid="pin-again" />
      </label>
      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      <Button type="submit" loading={busy} className="h-12">{t('bazar.join.pinSave')}</Button>
      <p className="text-small text-muted">{t('bazar.join.pinNote')}</p>
    </form>
  )
}

/** /bazar/invite-expired — no sign-in needed. */
export function BazarInviteExpired() {
  const [params] = useSearchParams()
  return <InviteRefused reason={params.get('reason') ?? 'expired'} />
}

const REFUSALS = ['used', 'expired', 'replaced', 'removed', 'missing_token'] as const

/**
 * B10: a seller link that can't be used — says why (used / expired / a newer link was sent / no longer a seller /
 * doesn't work) and to ask the owner for a new one.
 */
export function InviteRefused({ reason }: { reason: string }) {
  const { t } = useTranslation()
  const key = (REFUSALS as readonly string[]).includes(reason) ? reason : 'unknown'
  return (
    <div className="min-h-[100dvh] bg-base flex flex-col items-center justify-center gap-3 px-6 text-center"
         data-testid="bazar-invite-expired" data-reason={key}>
      <p className="text-h4 text-primary">{t('bazar.join.expiredTitle')}</p>
      <p className="text-body text-muted max-w-xs">{t(`bazar.invite.refused.${key}`)}</p>
    </div>
  )
}
