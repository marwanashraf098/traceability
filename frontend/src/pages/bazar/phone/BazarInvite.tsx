import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { refreshAccessToken, type Me } from '../../../api'
import { getAccessToken, setAccessToken } from '../../../auth'
import {
  BazarError, checkSellerInvite, currentUserId, inviteRefusal, redeemSellerInvite, type SellerInviteCheck,
} from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { announceSessionChange } from '../../../session'
import { Button, Spinner } from '../../../components/ui'
import { PhoneBrandRow, PhoneFrame } from './shared'
import { InviteRefused } from './BazarJoin'

/** The token rides in the fragment (#token=…): never sent to a server, so no link preview or log ever sees it. */
function tokenFromHash(hash: string): string | null {
  const v = new URLSearchParams(hash.replace(/^#/, '')).get('token')
  return v && v.trim() ? v.trim() : null
}

type Signed = { me: Me; userId: string | null } | null

/** Who this browser is signed in as (its one refresh cookie), if anyone — never sends anyone to /login. */
async function whoIsSignedIn(): Promise<Signed> {
  let access = getAccessToken()
  if (!access) {
    try { access = await refreshAccessToken() } catch { return null }
  }
  const res = await fetch('/api/v1/me', { headers: { Authorization: `Bearer ${access}` } })
  if (!res.ok) return null
  const me = await res.json() as Me
  return me && typeof me.name === 'string' ? { me, userId: currentUserId() } : null
}

/**
 * Bazar B10 — the seller invite's landing page (/bazar/invite?t=…#token=…). Opening the link uses nothing up (a
 * WhatsApp preview opens it first); this page reads the link (check), and only "Start selling" uses it (redeem) and
 * signs this browser in as the seller, then the unchanged B6b flow (/bazar/join → PIN → Sell).
 * Someone else already signed in on this browser → "You're signed in as … Continue as …?" — never a silent switch.
 * A link that can't be used says why (used / expired / replaced / removed) and to ask the owner for a new one.
 */
export default function BazarInvite() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const { hash } = useLocation()
  const tenant = params.get('t')
  const [token] = useState(() => tokenFromHash(hash))
  const [check, setCheck] = useState<SellerInviteCheck | null>(null)
  const [signed, setSigned] = useState<Signed | undefined>(undefined)
  const [refused, setRefused] = useState<string | null>(token ? null : 'missing_token')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!token) {
      // An app dropped the #token= fragment: the server logs the refusal (missing_token); the page already says why.
      checkSellerInvite(tenant, '').catch(() => {})
      return
    }
    let cancelled = false
    checkSellerInvite(tenant, token).then(c => {
      if (cancelled) return
      if (c.status === 'valid' || c.status === 'legacy') setCheck(c)
      else setRefused(c.status)
    }).catch(e => {
      if (cancelled) return
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.invite.failed'))
    })
    return () => { cancelled = true }
  }, [tenant, token, i18n.language, t])

  useEffect(() => {
    let cancelled = false
    whoIsSignedIn().then(s => { if (!cancelled) setSigned(s) }).catch(() => { if (!cancelled) setSigned(null) })
    return () => { cancelled = true }
  }, [])

  async function start() {
    if (!token) return
    setBusy(true)
    setError(null)
    try {
      const r = await redeemSellerInvite(tenant, token)
      setAccessToken(r.access_token)
      resetCapabilitiesCache()
      announceSessionChange()
      navigate(`/bazar/join/${encodeURIComponent(r.event_id)}`, { replace: true })
    } catch (e) {
      const reason = inviteRefusal(e)
      if (reason) setRefused(reason)
      else setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.invite.failed'))
    } finally {
      setBusy(false)
    }
  }

  if (refused) return <InviteRefused reason={refused} />

  const header = <PhoneBrandRow />
  if (!check || signed === undefined) {
    return (
      <PhoneFrame testId="bazar-invite" header={header}>
        {error ? <p role="alert" className="text-body text-critical">{error}</p>
          : <div className="flex justify-center py-10"><Spinner size={32} /></div>}
      </PhoneFrame>
    )
  }

  const seller = check.seller_name
  const someoneElse = signed && (!check.seller_id || signed.userId !== check.seller_id)
  return (
    <PhoneFrame testId="bazar-invite" header={header}>
      <div className="flex flex-col gap-4 py-4">
        {check.store_name && <p className="text-small text-muted" data-testid="invite-store">{check.store_name}</p>}
        <h1 className="text-h4 text-primary" data-testid="invite-event">
          {check.event_name ?? t('bazar.invite.titleNoName')}
        </h1>
        {seller && <p className="text-body text-primary">{t('bazar.invite.sellingAs', { name: seller })}</p>}

        {someoneElse ? (
          <div className="flex flex-col gap-3 p-3 rounded-xl border border-warning/40 bg-warning/10" data-testid="invite-signed-in">
            <p className="text-body text-primary">
              {t('bazar.invite.signedInAs', { name: signed.me.name, role: t(`users.roles.${signed.me.role}`) })}
            </p>
            <p className="text-body text-primary font-semibold">
              {seller ? t('bazar.invite.continueAs', { name: seller }) : t('bazar.invite.continueAsSeller')}
            </p>
            <p className="text-small text-muted">{t('bazar.invite.signsOut', { name: signed.me.name })}</p>
            <div className="flex gap-2">
              <Button variant="secondary" onClick={() => navigate('/', { replace: true })}>
                {t('bazar.invite.cancel')}
              </Button>
              <Button loading={busy} onClick={start}>{t('bazar.invite.continue')}</Button>
            </div>
          </div>
        ) : (
          <Button loading={busy} onClick={start}>{t('bazar.invite.start')}</Button>
        )}
        {error && <p role="alert" className="text-small text-critical">{error}</p>}
      </div>
    </PhoneFrame>
  )
}
