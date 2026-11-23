import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Copy, MessageCircle, UserPlus } from 'lucide-react'
import {
  BazarError, addBazarSeller, allowBazarWorkers, listBazarSellerCandidates, listBazarSellers, newBazarSellerLink,
  removeBazarSeller, whatsAppUrl, type BazarSeller, type BazarSellerLink, type BazarSellers,
} from '../../bazar/api'
import { Button, Input, Spinner } from '../../components/ui'
import { Chip } from './r2/parts'

/**
 * Bazar POS B6b — the event's sellers (owner / manager): who can sell, "Add seller" (a new worker from a name and an
 * optional phone, or an existing worker), each seller's one-time sign-in link (Copy / Send on WhatsApp — a fresh
 * link each time, 24 h, single use), Remove (signs them out everywhere). Warns while the event doesn't let workers
 * sell and offers to allow them. The computer's Sellers card and the phone's Sellers sheet are this panel.
 */
export function SellersPanel({ eventId }: { eventId: string }) {
  const { t, i18n } = useTranslation()
  const [data, setData] = useState<BazarSellers | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [link, setLink] = useState<(BazarSellerLink & { name: string; phone: string | null }) | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [confirmRemove, setConfirmRemove] = useState<BazarSeller | null>(null)

  const fail = useCallback((e: unknown) => {
    setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.sellers.failed'))
  }, [i18n.language, t])

  // A body without a sellers list reads as none (never a crashed page).
  const load = useCallback(() => listBazarSellers(eventId).then(d => {
    setData({ ...d, workers_allowed: d?.workers_allowed !== false, sellers: Array.isArray(d?.sellers) ? d.sellers : [] })
    setError(null)
  }).catch(fail),
    [eventId, fail])
  useEffect(() => { load() }, [load])

  const ended = data?.event_status === 'ended'

  async function sendLink(s: BazarSeller) {
    setBusy('link:' + s.user_id)
    try {
      const l = await newBazarSellerLink(eventId, s.user_id)
      setLink({ ...l, name: s.name, phone: s.phone })
      setError(null)
      load()
    } catch (e) { fail(e) } finally { setBusy(null) }
  }

  async function remove(s: BazarSeller) {
    setBusy('remove:' + s.user_id)
    try {
      await removeBazarSeller(eventId, s.user_id)
      setConfirmRemove(null)
      if (link && link.name === s.name) setLink(null)
      load()
    } catch (e) { fail(e) } finally { setBusy(null) }
  }

  async function allow() {
    setBusy('allow')
    try { await allowBazarWorkers(eventId); load() } catch (e) { fail(e) } finally { setBusy(null) }
  }

  if (!data) return error ? <p role="alert" className="text-body text-critical">{error}</p> : <Spinner />

  return (
    <div className="flex flex-col gap-3" data-testid="bazar-sellers">
      {!data.workers_allowed && !ended && (
        <div className="flex flex-col gap-2 p-3 rounded-xl border border-warning/40 bg-warning/10" data-testid="sellers-workers-warning">
          <p className="text-small text-primary">{t('bazar.sellers.workersNotAllowed')}</p>
          <Button variant="secondary" size="sm" className="self-start" loading={busy === 'allow'} onClick={allow}>
            {t('bazar.sellers.allowWorkers')}
          </Button>
        </div>
      )}
      {error && <p role="alert" className="text-small text-critical">{error}</p>}

      {link && <LinkBox link={link} onDone={() => setLink(null)} />}

      {data.sellers.length === 0 ? (
        <p className="text-body text-muted">{t('bazar.sellers.none')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line">
          {data.sellers.map(s => (
            <li key={s.user_id} className="py-2.5 flex flex-col gap-2" data-testid="seller-row">
              <div className="flex items-center justify-between gap-2">
                <div className="flex flex-col min-w-0">
                  <span className="font-semibold text-primary truncate">{s.name}</span>
                  {s.phone && <span className="text-small text-muted font-mono" dir="ltr">{s.phone}</span>}
                </div>
                <Chip tone={s.has_pin ? 'good' : 'neutral'} className="shrink-0">
                  {s.has_pin ? t('bazar.sellers.ready') : t('bazar.sellers.waiting')}
                </Chip>
              </div>
              {confirmRemove?.user_id === s.user_id ? (
                <div className="flex flex-col gap-2 p-2.5 rounded-lg bg-charcoal">
                  <p className="text-small text-primary" data-testid="seller-remove-confirm">
                    {t(s.remove_turns_off_account ? 'bazar.sellers.removeConfirmOff' : 'bazar.sellers.removeConfirmStay', { name: s.name })}
                  </p>
                  <div className="flex gap-2">
                    <Button size="sm" variant="secondary" onClick={() => setConfirmRemove(null)}>{t('bazar.sellers.keep')}</Button>
                    <Button size="sm" variant="danger" loading={busy === 'remove:' + s.user_id} onClick={() => remove(s)}>
                      {t('bazar.sellers.remove')}
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  <div className="flex flex-wrap gap-2">
                    {!ended && (
                      <Button size="sm" variant="secondary" loading={busy === 'link:' + s.user_id} onClick={() => sendLink(s)}>
                        {s.has_pin ? t('bazar.sellers.newLink') : t('bazar.sellers.getLink')}
                      </Button>
                    )}
                    <Button size="sm" variant="ghost" onClick={() => setConfirmRemove(s)}>{t('bazar.sellers.remove')}</Button>
                  </div>
                  {/* B10: a link was sent already — a new one cancels it. */}
                  {!ended && s.last_invited_at && (
                    <span className="text-small text-muted" data-testid="seller-row-replaces">{t('bazar.sellers.replacesNote')}</span>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {!ended && (adding
        ? <AddSellerForm eventId={eventId} onCancel={() => setAdding(false)} onAdded={(l, name, phone) => {
            setAdding(false); setLink({ ...l, name, phone }); load()
          }} />
        : <Button variant="secondary" iconStart={UserPlus} onClick={() => setAdding(true)} className="self-start"
                  data-testid="add-seller">{t('bazar.sellers.add')}</Button>)}
    </div>
  )
}

function AddSellerForm({ eventId, onCancel, onAdded }: {
  eventId: string
  onCancel: () => void
  onAdded: (link: BazarSellerLink, name: string, phone: string | null) => void
}) {
  const { t, i18n } = useTranslation()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [pick, setPick] = useState('')
  const [candidates, setCandidates] = useState<{ user_id: string; name: string; phone: string | null }[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listBazarSellerCandidates(eventId).then(c => setCandidates(Array.isArray(c) ? c : [])).catch(() => setCandidates([]))
  }, [eventId])

  async function submit() {
    const existing = candidates.find(c => c.user_id === pick)
    if (!existing && !name.trim()) { setError(t('bazar.sellers.nameRequired')); return }
    setBusy(true)
    try {
      const r = existing
        ? await addBazarSeller(eventId, { user_id: existing.user_id })
        : await addBazarSeller(eventId, { name: name.trim(), phone: phone.trim() || null })
      onAdded(r, r.seller.name, r.seller.phone)
    } catch (e) {
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.sellers.failed'))
    } finally { setBusy(false) }
  }

  return (
    <form className="flex flex-col gap-2.5 p-3 rounded-xl border border-line" data-testid="add-seller-form"
          onSubmit={e => { e.preventDefault(); submit() }}>
      <h3 className="font-semibold text-primary">{t('bazar.sellers.add')}</h3>
      {candidates.length > 0 && (
        <label className="flex flex-col gap-1 text-small text-muted">
          {t('bazar.sellers.existing')}
          <select className="h-10 rounded-lg border border-line bg-surface px-3 text-body text-primary"
                  value={pick} onChange={e => setPick(e.target.value)} data-testid="seller-existing">
            <option value="">{t('bazar.sellers.newPerson')}</option>
            {candidates.map(c => <option key={c.user_id} value={c.user_id}>{c.name}</option>)}
          </select>
        </label>
      )}
      {!pick && (
        <>
          <label className="flex flex-col gap-1 text-small text-muted">
            {t('bazar.sellers.name')}
            <Input value={name} onChange={e => setName(e.target.value)} maxLength={80} autoComplete="off"
                   data-testid="seller-name" />
          </label>
          <label className="flex flex-col gap-1 text-small text-muted">
            {t('bazar.sellers.phone')}
            <Input value={phone} onChange={e => setPhone(e.target.value)} inputMode="tel" dir="ltr" autoComplete="off"
                   data-testid="seller-phone" />
            <span>{t('bazar.sellers.phoneHint')}</span>
          </label>
        </>
      )}
      <p className="text-small text-muted">{t('bazar.sellers.addNote')}</p>
      {error && <p role="alert" className="text-small text-critical">{error}</p>}
      <div className="flex gap-2">
        <Button type="button" variant="secondary" onClick={onCancel}>{t('bazar.sellers.cancel')}</Button>
        <Button type="submit" loading={busy}>{t('bazar.sellers.createLink')}</Button>
      </div>
    </form>
  )
}

/** The link just made: Copy, Send on WhatsApp (wa.me, the event name and the link prefilled). Shown once. */
function LinkBox({ link, onDone }: { link: BazarSellerLink & { name: string; phone: string | null }; onDone: () => void }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const text = t('bazar.sellers.whatsAppText', { name: link.name, event: link.event_name, link: link.link })
  return (
    <div className="flex flex-col gap-2 p-3 rounded-xl border border-trace-blue/40 bg-trace-blue/10" data-testid="seller-link">
      <p className="text-small font-semibold text-primary">{t('bazar.sellers.linkFor', { name: link.name })}</p>
      <code className="text-small text-primary break-all font-mono" dir="ltr" data-testid="seller-link-url">{link.link}</code>
      <p className="text-small text-muted">{t('bazar.sellers.linkNote')}</p>
      {/* B10: sending again cancels the earlier link (it shows "a newer link was sent" to whoever opens it). */}
      <p className="text-small text-muted" data-testid="seller-link-replaces">{t('bazar.sellers.replacesNote')}</p>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" variant="secondary" iconStart={Copy} onClick={() => {
          navigator.clipboard?.writeText(link.link).then(() => setCopied(true)).catch(() => setCopied(false))
        }}>{copied ? t('bazar.sellers.copied') : t('bazar.sellers.copy')}</Button>
        <a href={whatsAppUrl(text, link.phone)} target="_blank" rel="noopener noreferrer" data-testid="seller-whatsapp"
           className="h-8 px-3 rounded-lg bg-[#16A34A] hover:bg-[#15803D] text-white text-small font-semibold inline-flex items-center gap-1.5">
          <MessageCircle size={16} aria-hidden />{t('bazar.sellers.whatsApp')}
        </a>
        <Button size="sm" variant="ghost" onClick={onDone}>{t('bazar.sellers.done')}</Button>
      </div>
    </div>
  )
}
