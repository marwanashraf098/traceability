import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText, Loader2, MoreVertical, Users, UserRoundCog } from 'lucide-react'
import { switchPin } from '../../../api'
import { setAccessToken } from '../../../auth'
import { BazarError, getBazarSellerRoster, managesBazar } from '../../../bazar/api'
import { resetCapabilitiesCache } from '../../../capabilities'
import { getPriceSheet } from '../../../bazar/catalog'
import { Button, Input, Spinner } from '../../../components/ui'
import { SellersPanel } from '../SellersPanel'
import { Sheet } from './shared'

type View = 'menu' | 'switch' | 'sellers' | null

/**
 * Bazar POS B6b — the phone bazar header's menu: "Switch seller" (a shared phone: this event's sellers, then their
 * PIN through the existing PIN switch /auth/pin) and, for an owner / manager, "Sellers" (the same panel as the
 * computer's Sellers card).
 */
export function BazarMenu({ eventId }: { eventId: string }) {
  const { t, i18n } = useTranslation()
  const [view, setView] = useState<View>(null)
  const [sharing, setSharing] = useState<'busy' | 'failed' | null>(null)
  // B7b: the A4 price sheet — shared as a PDF (Web Share API), else downloaded. Any seller of the event.
  async function priceSheet() {
    setSharing('busy')
    try { await getPriceSheet(eventId, i18n.language, true); setSharing(null); setView(null) }
    catch { setSharing('failed') }
  }
  const manager = managesBazar()
  const item = 'h-12 rounded-xl border border-line bg-surface px-4 font-semibold text-primary flex items-center gap-3 text-start'
  return (
    <>
      <button type="button" aria-label={t('bazar.menu.open')} data-testid="bazar-menu"
              onClick={() => setView('menu')}
              className="w-11 h-11 -me-2 flex items-center justify-center text-sidebar-active">
        <MoreVertical size={20} />
      </button>
      {view === 'menu' && (
        <Sheet title={t('bazar.menu.title')} onClose={() => setView(null)} testId="bazar-menu-sheet">
          <button type="button" className={item} onClick={() => setView('switch')} data-testid="menu-switch-seller">
            <UserRoundCog size={18} aria-hidden />{t('bazar.menu.switchSeller')}
          </button>
          <button type="button" className={item} onClick={priceSheet} disabled={sharing === 'busy'} data-testid="menu-price-sheet">
            {sharing === 'busy' ? <Loader2 size={18} className="animate-spin" aria-hidden /> : <FileText size={18} aria-hidden />}
            {t('bazar.pick.priceSheet')}
          </button>
          {sharing === 'failed' && <p role="alert" className="text-small text-critical">{t('bazar.pick.priceSheetFailed')}</p>}
          {manager && (
            <button type="button" className={item} onClick={() => setView('sellers')} data-testid="menu-sellers">
              <Users size={18} aria-hidden />{t('bazar.menu.sellers')}
            </button>
          )}
        </Sheet>
      )}
      {view === 'switch' && (
        <Sheet title={t('bazar.menu.switchSeller')} onClose={() => setView(null)} testId="bazar-switch-seller">
          <SwitchSeller eventId={eventId} />
        </Sheet>
      )}
      {view === 'sellers' && (
        <Sheet title={t('bazar.menu.sellers')} onClose={() => setView(null)} testId="bazar-sellers-sheet">
          <SellersPanel eventId={eventId} />
        </Sheet>
      )}
    </>
  )
}

function SwitchSeller({ eventId }: { eventId: string }) {
  const { t, i18n } = useTranslation()
  const [roster, setRoster] = useState<{ id: string; name: string; locked: boolean }[] | null>(null)
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null)
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getBazarSellerRoster(eventId).then(setRoster).catch(e => {
      setRoster([])
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.menu.rosterFailed'))
    })
  }, [eventId, i18n.language, t])

  async function submit() {
    if (!picked || pin.length !== 4) return
    setBusy(true)
    const r = await switchPin(picked.id, pin).catch(() => null)
    setBusy(false)
    if (r?.ok) {
      setAccessToken(r.accessToken)
      resetCapabilitiesCache()
      window.location.assign(`/bazar/${encodeURIComponent(eventId)}/home`)
      return
    }
    setPin('')
    setError(r && r.status === 423 ? t('bazar.menu.locked') : t('bazar.menu.wrongPin'))
  }

  if (!roster) return <Spinner />
  if (!picked) {
    return (
      <div className="flex flex-col gap-2" data-testid="switch-roster">
        {error && <p role="alert" className="text-small text-critical">{error}</p>}
        {roster.length === 0 && !error && <p className="text-body text-muted">{t('bazar.menu.noSellers')}</p>}
        {roster.map(r => (
          <button key={r.id} type="button" disabled={r.locked}
                  onClick={() => { setPicked(r); setError(null) }}
                  className="h-12 rounded-xl border border-line bg-surface px-4 font-semibold text-primary text-start disabled:opacity-50">
            {r.name}{r.locked ? ` · ${t('bazar.menu.lockedShort')}` : ''}
          </button>
        ))}
      </div>
    )
  }
  return (
    <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); submit() }} data-testid="switch-pin">
      <p className="text-body text-primary">{t('bazar.menu.pinFor', { name: picked.name })}</p>
      <Input value={pin} onChange={e => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))} inputMode="numeric"
             type="password" autoComplete="off" dir="ltr" maxLength={4} autoFocus
             className="text-center tracking-[0.6em] text-h4" aria-label={t('bazar.join.pin')} />
      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      <div className="flex gap-2">
        <Button type="button" variant="secondary" onClick={() => { setPicked(null); setPin(''); setError(null) }}>
          {t('bazar.menu.back')}
        </Button>
        <Button type="submit" loading={busy} disabled={pin.length !== 4} className="flex-1">{t('bazar.menu.switch')}</Button>
      </div>
    </form>
  )
}
