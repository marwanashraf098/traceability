import { CSSProperties, FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import {
  getConfig, lookup, submit, LookupResult, PickupDistrict, PortalConfig, SubmitResult,
} from './api'
import { palette } from './brand'
import { applyDocumentLanguage, PortalLang, saveLanguage } from './i18n'

/**
 * Returns portal Step 4e-B — the customer-facing flow on returns.tracedtech.com/{slug}:
 * P1 find order → P2 choose items → P3 details & send → P4 sent, plus the not-found (P5),
 * too-many-attempts (P6) and unavailable states. Mockups: design/returns-portal/P1–P7.
 *
 * Self-contained: talks only to the three public portal endpoints (./api), uses its own
 * i18n instance and plain CSS — nothing from the merchant app.
 *
 * Step 4c-2: when lookup offers a pickup area (the store books Bosta pickups and the delivery
 * city is known), P3's Pickup card shows the City (read-only) and a required Area select,
 * grouped by zone, names in the current language — as in the P3 mockup. Send stays disabled
 * until an area is chosen. Without an offer, P3 keeps its previous copy.
 */

export const NOTE_MAX = 300
// Same rule as PortalService.EMAIL on the backend.
export const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

type Step = 'start' | 'items' | 'details' | 'sent'
type StartBanner = 'notFound' | 'throttled' | 'generic' | 'expired' | 'conflict'
type SendBanner = 'invalid' | 'generic' | 'submitThrottled'
interface Selection { qty: number; reason: string }

/** Slug = first path segment. */
export function slugFromPath(pathname: string): string | null {
  const first = pathname.split('/').filter(Boolean)[0]
  return first ? decodeURIComponent(first) : null
}

/** Consecutive districts sharing a zone (the backend sorts by zone), labelled in the current language. */
export function groupByZone(districts: PickupDistrict[], lang: string) {
  const groups: { zone: string | null; districts: PickupDistrict[] }[] = []
  for (const d of districts) {
    const zone = (lang === 'ar' ? d.zoneNameAr || d.zoneName : d.zoneName) || null
    const last = groups[groups.length - 1]
    if (last && last.zone === zone) last.districts.push(d)
    else groups.push({ zone, districts: [d] })
  }
  return groups
}

/** "mona@example.com" → "m•••@example.com". */
export function maskEmail(email: string): string {
  const at = email.lastIndexOf('@')
  if (at < 1) return email
  return `${Array.from(email.slice(0, at))[0]}•••${email.slice(at)}`
}

function shortDate(iso: string, lang: string): string {
  const locale = lang === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB'
  return new Date(iso).toLocaleDateString(locale, { day: 'numeric', month: 'short' })
}

export default function PortalApp({ slug }: { slug: string | null }) {
  const { t, i18n } = useTranslation()
  const lang = (i18n.language === 'ar' ? 'ar' : 'en') as PortalLang

  const [config, setConfig] = useState<PortalConfig | null>(null)
  const [configState, setConfigState] = useState<'loading' | 'ok' | 'unavailable' | 'error'>(slug ? 'loading' : 'unavailable')

  const [step, setStep] = useState<Step>('start')
  const [orderNumber, setOrderNumber] = useState('')
  const [phone, setPhone] = useState('')
  const [finding, setFinding] = useState(false)
  const [startBanner, setStartBanner] = useState<StartBanner | null>(null)
  const [throttledOrder, setThrottledOrder] = useState<string | null>(null)
  const [missing, setMissing] = useState<{ order: boolean; phone: boolean }>({ order: false, phone: false })
  const orderInputRef = useRef<HTMLInputElement>(null)
  const phoneInputRef = useRef<HTMLInputElement>(null)

  const [order, setOrder] = useState<LookupResult | null>(null)
  const [selections, setSelections] = useState<Record<string, Selection>>({})

  const [areaId, setAreaId] = useState('')
  const [note, setNote] = useState('')
  const [email, setEmail] = useState('')
  const [emailError, setEmailError] = useState(false)
  const [sending, setSending] = useState(false)
  const sendingRef = useRef(false)
  const [sendBanner, setSendBanner] = useState<SendBanner | null>(null)

  const [result, setResult] = useState<SubmitResult | null>(null)
  const [sentEmail, setSentEmail] = useState<string | null>(null)

  const headingRef = useRef<HTMLHeadingElement>(null)
  const firstRender = useRef(true)

  useEffect(() => { applyDocumentLanguage(lang) }, [lang])

  const loadConfig = useCallback(async () => {
    if (!slug) return
    setConfigState('loading')
    const res = await getConfig(slug)
    if (res.ok) {
      setConfig(res.data)
      setConfigState('ok')
    } else {
      setConfigState(res.failure === 'notFound' ? 'unavailable' : 'error')
    }
  }, [slug])

  useEffect(() => { loadConfig() }, [loadConfig])

  // Focus moves to the step heading on every step change (not on first load).
  useEffect(() => {
    if (firstRender.current) { firstRender.current = false; return }
    headingRef.current?.focus()
  }, [step])

  const colors = useMemo(() => palette(config?.brandColor), [config?.brandColor])
  const rootStyle = {
    '--brand': colors.brand,
    '--on-brand': colors.onBrand,
    '--accent-text': colors.accentText,
    '--accent-soft': colors.accentSoft,
  } as CSSProperties

  function toggleLanguage() {
    const next: PortalLang = lang === 'ar' ? 'en' : 'ar'
    saveLanguage(next)
    i18n.changeLanguage(next)
  }

  const store = config?.storeName ?? ''

  // ── P1 ────────────────────────────────────────────────────────────────────
  async function find(e: FormEvent) {
    e.preventDefault()
    if (!slug || finding) return
    // Both fields are required; an empty one is flagged locally and never sent (a blank
    // lookup would only count as a failed attempt against the per-order throttle).
    const noOrder = !orderNumber.trim(), noPhone = !phone.trim()
    if (noOrder || noPhone) {
      setMissing({ order: noOrder, phone: noPhone })
      ;(noOrder ? orderInputRef : phoneInputRef).current?.focus()
      return
    }
    setFinding(true)
    const res = await lookup(slug, orderNumber, phone)
    setFinding(false)
    if (res.ok) {
      setOrder(res.data)
      setSelections({})
      setAreaId(res.data.pickup?.preselectedDistrictId ?? '')
      setStartBanner(null)
      setThrottledOrder(null)
      setSendBanner(null)
      setStep('items')
      return
    }
    if (res.failure === 'notFound') setStartBanner('notFound')
    else if (res.failure === 'throttled') { setStartBanner('throttled'); setThrottledOrder(orderNumber) }
    else setStartBanner('generic')
  }

  // ── P2 ────────────────────────────────────────────────────────────────────
  const selectedLines = order ? order.lines.filter(l => (selections[l.variantId]?.qty ?? 0) > 0) : []
  const selectedCount = selectedLines.reduce((n, l) => n + selections[l.variantId].qty, 0)
  const missingReason = selectedLines.some(l => !selections[l.variantId].reason)
  const canContinue = selectedLines.length > 0 && !missingReason

  function setQty(variantId: string, qty: number) {
    setSelections(s => ({ ...s, [variantId]: { qty, reason: s[variantId]?.reason ?? '' } }))
  }

  function setReason(variantId: string, reason: string) {
    setSelections(s => ({ ...s, [variantId]: { qty: s[variantId]?.qty ?? 0, reason } }))
  }

  // ── P3 ────────────────────────────────────────────────────────────────────
  const pickup = order?.pickup ?? null
  const areaMissing = pickup != null && !areaId

  async function send() {
    if (!slug || !order || sendingRef.current || areaMissing) return
    const trimmedEmail = email.trim()
    if (trimmedEmail && (trimmedEmail.length > 254 || !EMAIL_RE.test(trimmedEmail))) {
      setEmailError(true)
      return
    }
    sendingRef.current = true
    setSending(true)
    setSendBanner(null)
    const res = await submit(slug, order.token, {
      lines: selectedLines.map(l => ({
        variantId: l.variantId, quantity: selections[l.variantId].qty, reasonCode: selections[l.variantId].reason,
      })),
      ...(trimmedEmail ? { email: trimmedEmail } : {}),
      ...(note.trim() ? { note } : {}),
      ...(pickup && areaId ? { districtId: areaId } : {}),
    })
    sendingRef.current = false
    setSending(false)
    if (res.ok) {
      setResult(res.data)
      setSentEmail(trimmedEmail || null)
      setStep('sent')
      return
    }
    if (res.failure === 'unauthorized' || res.failure === 'conflict') {
      setOrder(null)
      setSelections({})
      setStartBanner(res.failure === 'unauthorized' ? 'expired' : 'conflict')
      setStep('start')
      return
    }
    setSendBanner(res.failure === 'invalid' ? 'invalid' : res.failure === 'throttled' ? 'submitThrottled' : 'generic')
  }

  // ── Render ────────────────────────────────────────────────────────────────
  const header = (onBack?: () => void) => (
    <Header
      onBack={onBack}
      config={configState === 'ok' ? config : null}
      onToggleLanguage={toggleLanguage}
    />
  )

  if (configState === 'unavailable') {
    return (
      <Shell style={rootStyle} header={header()}>
        <div className="pp-intro">
          <h1 className="pp-h1" tabIndex={-1}>{t('unavailable.title')}</h1>
          <p className="pp-lead">{t('unavailable.body')}</p>
        </div>
      </Shell>
    )
  }

  if (configState === 'error') {
    return (
      <Shell style={rootStyle} header={header()}>
        <div className="pp-banner pp-banner--error" role="alert"><p>{t('loadError.title')}</p></div>
        <button type="button" className="pp-btn pp-btn--primary" onClick={loadConfig}>{t('loadError.retry')}</button>
      </Shell>
    )
  }

  if (configState === 'loading' || !config) {
    return <Shell style={rootStyle} header={header()}><div aria-busy="true" className="pp-loading" /></Shell>
  }

  if (step === 'items' && order) {
    return (
      <Shell
        style={rootStyle}
        header={header(() => setStep('start'))}
        footer={(
          <div className="pp-bar">
            <div className="pp-bar__count" aria-live="polite">
              {selectedCount > 0
                ? (missingReason ? t('p2.reasonMissing') : t('p2.selected', { count: selectedCount }))
                : t('p2.noneSelected')}
            </div>
            <button type="button" className="pp-btn pp-btn--primary" disabled={!canContinue} onClick={() => setStep('details')}>
              {t('p2.continue')}
            </button>
          </div>
        )}
        hidePowered
      >
        <div className="pp-stephead">
          <div className="pp-eyebrow">{t('p2.step', { step: 1, total: 2 })}</div>
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t('p2.title')}</h1>
          <div className="pp-muted">
            {t('p2.orderLine', { number: '⁨' + order.orderNumber + '⁩', date: shortDate(order.deliveredAt, lang) })}
          </div>
        </div>
        {order.lines.map(line => {
          const sel = selections[line.variantId] ?? { qty: 0, reason: '' }
          const disabled = line.nonReturnable || line.returnableQuantity <= 0
          const reasonId = `reason-${line.variantId}`
          if (disabled) {
            return (
              <section key={line.variantId} className="pp-item pp-item--disabled" data-testid="portal-line">
                <Thumb src={line.imageUrl} muted />
                <div className="pp-item__text">
                  <div className="pp-item__title"><bdi>{line.productTitle}</bdi></div>
                  <div className="pp-item__sub">
                    {line.variantTitle && <><bdi>{line.variantTitle}</bdi> · </>}
                    {line.nonReturnable ? t('p2.cantReturn') : t('p2.alreadyRequested')}
                  </div>
                </div>
              </section>
            )
          }
          return (
            <section key={line.variantId} className={'pp-item' + (sel.qty > 0 ? ' pp-item--selected' : '')} data-testid="portal-line">
              <div className="pp-item__head">
                <Thumb src={line.imageUrl} />
                <div className="pp-item__text">
                  <div className="pp-item__title"><bdi>{line.productTitle}</bdi></div>
                  {line.variantTitle && <div className="pp-item__sub"><bdi>{line.variantTitle}</bdi></div>}
                </div>
              </div>
              <div className="pp-qty" role="group" aria-label={`${t('p2.quantity')} — ${line.productTitle}`}>
                <div className="pp-qty__label">{t('p2.quantity')}</div>
                <button
                  type="button" className="pp-stepbtn" aria-label={t('p2.decrease')}
                  disabled={sel.qty <= 0} onClick={() => setQty(line.variantId, sel.qty - 1)}
                >−</button>
                <div className="pp-qty__value" aria-live="polite">{sel.qty}</div>
                <button
                  type="button" className="pp-stepbtn" aria-label={t('p2.increase')}
                  disabled={sel.qty >= line.returnableQuantity} onClick={() => setQty(line.variantId, sel.qty + 1)}
                >+</button>
                <div className="pp-muted pp-qty__of">{t('p2.of', { count: line.returnableQuantity })}</div>
              </div>
              {sel.qty > 0 && (
                <div className="pp-field">
                  <label htmlFor={reasonId} className="pp-label">{t('p2.reasonLabel')}</label>
                  <select
                    id={reasonId} className="pp-input" required
                    aria-invalid={!sel.reason}
                    value={sel.reason}
                    onChange={e => setReason(line.variantId, e.target.value)}
                  >
                    <option value="" disabled>{t('p2.reasonPlaceholder')}</option>
                    {config.reasonCodes.map(code => (
                      <option key={code} value={code}>{t(`reasons.${code}`, { defaultValue: code })}</option>
                    ))}
                  </select>
                </div>
              )}
            </section>
          )
        })}
      </Shell>
    )
  }

  if (step === 'details' && order) {
    return (
      <Shell style={rootStyle} header={header(() => setStep('items'))}>
        <div className="pp-stephead">
          <div className="pp-eyebrow">{t('p2.step', { step: 2, total: 2 })}</div>
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t('p3.title')}</h1>
        </div>

        <section className="pp-card">
          <div className="pp-card__head">
            <div className="pp-eyebrow">{t('p3.returning')}</div>
            <button type="button" className="pp-link" onClick={() => setStep('items')}>{t('p3.edit')}</button>
          </div>
          {selectedLines.map(l => (
            <div key={l.variantId} className="pp-item__head" data-testid="summary-line">
              <Thumb src={l.imageUrl} small />
              <div className="pp-item__text">
                <div className="pp-item__title pp-item__title--sm">
                  <bdi>{l.productTitle}{l.variantTitle && ` · ${l.variantTitle}`}</bdi>
                </div>
                <div className="pp-item__sub">
                  {t('p3.lineQty', { count: selections[l.variantId].qty, reason: t(`reasons.${selections[l.variantId].reason}`) })}
                </div>
              </div>
            </div>
          ))}
        </section>

        <section className="pp-card">
          <div className="pp-eyebrow">{t('p3.pickup')}</div>
          <p className="pp-muted pp-small">{t(config.pickupBooking || pickup ? 'p3.pickupTextBooking' : 'p3.pickupText')}</p>
          {pickup && (
            <>
              <div className="pp-field">
                <label htmlFor="pp-city" className="pp-label">{t('p3.cityLabel')}</label>
                <input
                  id="pp-city" className="pp-input pp-input--readonly" type="text" readOnly
                  value={lang === 'ar' ? pickup.cityNameAr || pickup.cityName : pickup.cityName}
                />
              </div>
              <div className="pp-field">
                <label htmlFor="pp-area" className="pp-label">{t('p3.areaLabel')}</label>
                <select
                  id="pp-area" className="pp-input" required
                  aria-invalid={areaMissing}
                  value={areaId}
                  onChange={e => setAreaId(e.target.value)}
                >
                  <option value="" disabled>{t('p3.areaPlaceholder')}</option>
                  {groupByZone(pickup.districts, lang).map(g => {
                    const options = g.districts.map(d => (
                      <option key={d.id} value={d.id}>{lang === 'ar' ? d.nameAr || d.name : d.name}</option>
                    ))
                    return g.zone ? <optgroup key={g.zone} label={g.zone}>{options}</optgroup> : options
                  })}
                </select>
              </div>
            </>
          )}
        </section>

        <div className="pp-field">
          <label htmlFor="pp-note" className="pp-label">
            {t('p3.noteLabel')} <span className="pp-optional">{t('common.optional')}</span>
          </label>
          <textarea
            id="pp-note" className="pp-input pp-textarea" rows={3} maxLength={NOTE_MAX} dir="auto"
            aria-describedby="pp-note-count"
            value={note} onChange={e => setNote(e.target.value)}
          />
          <div id="pp-note-count" className="pp-counter" dir="ltr">{note.length} / {NOTE_MAX}</div>
        </div>

        <div className="pp-field">
          <label htmlFor="pp-email" className="pp-label">
            {t('p3.emailLabel')} <span className="pp-optional">{t('common.optional')}</span>
          </label>
          <input
            id="pp-email" className="pp-input" type="email" inputMode="email" autoComplete="email" dir="ltr"
            placeholder={t('p3.emailPlaceholder')}
            aria-invalid={emailError} aria-describedby={emailError ? 'pp-email-error' : undefined}
            value={email} onChange={e => { setEmail(e.target.value); setEmailError(false) }}
          />
          {emailError && <p id="pp-email-error" className="pp-fielderror" role="alert">{t('p3.emailInvalid')}</p>}
        </div>

        {sendBanner && (
          <div className="pp-banner pp-banner--error" role="alert" data-testid="send-banner">
            <p>{t(`errors.${sendBanner}`)}</p>
          </div>
        )}

        <button type="button" className="pp-btn pp-btn--primary" disabled={sending || areaMissing} aria-busy={sending} onClick={send}>
          {sending ? t('p3.sending') : t('p3.send')}
        </button>

        {config.policyText && <PolicyLine store={store} policyText={config.policyText} />}
      </Shell>
    )
  }

  if (step === 'sent' && result) {
    return (
      <Shell style={rootStyle} header={header()}>
        <SentScreen
          headingRef={headingRef}
          store={store}
          result={result}
          email={sentEmail}
          pickupBooking={config.pickupBooking}
        />
      </Shell>
    )
  }

  // ── P1 / P5 / P6 ──────────────────────────────────────────────────────────
  const throttled = startBanner === 'throttled' && throttledOrder === orderNumber
  const bannerClass = startBanner === 'throttled' ? 'pp-banner pp-banner--warn' : 'pp-banner pp-banner--error'
  return (
    <Shell style={rootStyle} header={header()}>
      <div className="pp-intro">
        <h1 className="pp-h1" tabIndex={-1} ref={headingRef}>{t('p1.title')}</h1>
        <p className="pp-lead">{t('p1.intro')}</p>
      </div>

      {startBanner && (
        <div className={bannerClass} role="alert" data-testid="start-banner">
          <AlertIcon />
          <p>{t(`errors.${startBanner}`, { store })}</p>
        </div>
      )}

      <form className="pp-form" onSubmit={find} noValidate>
        <div className="pp-field">
          <label htmlFor="pp-order" className="pp-label">{t('p1.orderLabel')}</label>
          <input
            id="pp-order" className="pp-input pp-input--lg" type="text" autoComplete="off" dir="ltr"
            placeholder={t('p1.orderPlaceholder')} required ref={orderInputRef}
            aria-invalid={missing.order} aria-describedby={missing.order ? 'pp-order-missing' : undefined}
            value={orderNumber} onChange={e => { setOrderNumber(e.target.value); setMissing(m => ({ ...m, order: false })) }}
          />
          {missing.order && <p id="pp-order-missing" className="pp-fielderror">{t('p1.orderMissing')}</p>}
        </div>
        <div className="pp-field">
          <label htmlFor="pp-phone" className="pp-label">{t('p1.phoneLabel')}</label>
          <input
            id="pp-phone" className="pp-input pp-input--lg" type="tel" inputMode="tel" autoComplete="tel" dir="ltr"
            placeholder={t('p1.phonePlaceholder')} required ref={phoneInputRef}
            aria-invalid={missing.phone} aria-describedby={missing.phone ? 'pp-phone-missing' : undefined}
            value={phone} onChange={e => { setPhone(e.target.value); setMissing(m => ({ ...m, phone: false })) }}
          />
          {missing.phone && <p id="pp-phone-missing" className="pp-fielderror">{t('p1.phoneMissing')}</p>}
        </div>
        <button
          type="submit" className="pp-btn pp-btn--primary"
          disabled={finding || throttled}
          aria-busy={finding}
        >
          {finding ? t('p1.finding') : startBanner === 'notFound' ? t('p1.tryAgain') : t('p1.find')}
        </button>
      </form>

      {!startBanner && (
        <div className="pp-note">
          <InfoIcon />
          <p>
            {t('p1.windowNote', { count: config.returnWindowDays })}{' '}
            {t(config.pickupBooking ? 'p1.pickupNoteBooking' : 'p1.pickupNote')}
          </p>
        </div>
      )}
    </Shell>
  )
}

// ── Pieces ──────────────────────────────────────────────────────────────────

function Shell({
  style, header, footer, children, hidePowered,
}: { style: CSSProperties; header: ReactNode; footer?: ReactNode; children: ReactNode; hidePowered?: boolean }) {
  const { t } = useTranslation()
  return (
    <div className="pp-root" style={style}>
      <div className="pp-column">
        {header}
        <main className="pp-main">{children}</main>
        {footer}
        {!hidePowered && <footer className="pp-powered">{t('common.poweredBy')}</footer>}
      </div>
    </div>
  )
}

function Header({
  onBack, config, onToggleLanguage,
}: { onBack?: () => void; config: PortalConfig | null; onToggleLanguage: () => void }) {
  const { t } = useTranslation()
  const [logoFailed, setLogoFailed] = useState(false)
  return (
    <header className={'pp-header' + (onBack ? '' : ' pp-header--noback')}>
      {onBack && (
        <button type="button" className="pp-iconbtn" aria-label={t('common.back')} onClick={onBack}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className="pp-flip">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
      )}
      {config && (config.logoUrl && !logoFailed
        ? <img className="pp-logo" src={config.logoUrl} alt={config.storeName} onError={() => setLogoFailed(true)} />
        : <div className="pp-wordmark" dir="auto">{config.storeName}</div>)}
      <div className="pp-spacer" />
      <button
        type="button" className="pp-lang" lang={t('common.switchToLang')}
        aria-label={t('common.switchLabel')} onClick={onToggleLanguage}
      >
        {t('common.switchTo')}
      </button>
    </header>
  )
}

function Thumb({ src, muted, small }: { src: string | null; muted?: boolean; small?: boolean }) {
  const [failed, setFailed] = useState(false)
  const cls = 'pp-thumb' + (muted ? ' pp-thumb--muted' : '') + (small ? ' pp-thumb--sm' : '')
  if (src && !failed) {
    return <img className={cls} src={src} alt="" loading="lazy" onError={() => setFailed(true)} />
  }
  return (
    <div className={cls} aria-hidden="true">
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M21 8l-9-5-9 5v8l9 5 9-5V8z" /><path d="M3 8l9 5 9-5" /><path d="M12 13v8" />
      </svg>
    </div>
  )
}

function AlertIcon() {
  return (
    <svg className="pp-banner__icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16h.01" />
    </svg>
  )
}

function InfoIcon() {
  return (
    <svg className="pp-note__icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" /><path d="M12 11v5M12 8h.01" />
    </svg>
  )
}

function PolicyLine({ store, policyText }: { store: string; policyText: string }) {
  const { t } = useTranslation()
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  return (
    <>
      <p className="pp-fineprint">
        <Trans
          t={t}
          i18nKey="p3.policy"
          values={{ store }}
          components={{
            policy: <button type="button" className="pp-inlinelink" ref={triggerRef} onClick={() => setOpen(true)} />,
          }}
        />
      </p>
      {open && (
        <PolicyDialog
          text={policyText}
          onClose={() => { setOpen(false); triggerRef.current?.focus() }}
        />
      )}
    </>
  )
}

function PolicyDialog({ text, onClose }: { text: string; onClose: () => void }) {
  const { t } = useTranslation()
  const closeRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') { e.preventDefault(); onClose() }
      if (e.key === 'Tab' && dialogRef.current) {
        const focusables = dialogRef.current.querySelectorAll<HTMLElement>('button, [tabindex="0"]')
        const first = focusables[0], last = focusables[focusables.length - 1]
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="pp-overlay" onClick={onClose}>
      <div
        ref={dialogRef}
        className="pp-dialog" role="dialog" aria-modal="true" aria-labelledby="pp-policy-title"
        onClick={e => e.stopPropagation()}
      >
        <div className="pp-dialog__head">
          <h2 id="pp-policy-title" className="pp-h3">{t('p3.policyTitle')}</h2>
          <button type="button" ref={closeRef} className="pp-iconbtn" aria-label={t('common.close')} onClick={onClose}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        <div className="pp-dialog__body" tabIndex={0} dir="auto">{text}</div>
      </div>
    </div>
  )
}

function SentScreen({
  headingRef, store, result, email, pickupBooking,
}: {
  headingRef: React.RefObject<HTMLHeadingElement>
  store: string
  result: SubmitResult
  email: string | null
  pickupBooking: boolean
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const [manual, setManual] = useState(false)
  const refEl = useRef<HTMLDivElement>(null)
  const approved = result.status === 'approved'

  async function copy() {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('no clipboard')
      await navigator.clipboard.writeText(result.reference)
      setCopied(true)
      setManual(false)
    } catch {
      // Fall back to selecting the reference so it can be copied by hand.
      const sel = window.getSelection()
      if (sel && refEl.current) sel.selectAllChildren(refEl.current)
      setManual(true)
    }
  }

  const steps = [
    ...(approved ? [] : [t('p4.stepReview', { store })]),
    pickupBooking ? t('p4.stepCollectBooking') : t('p4.stepCollect', { store }),
    t('p4.stepRefund'),
  ]

  return (
    <>
      <div className="pp-success">
        <div className="pp-success__icon" aria-hidden="true">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M5 12.5l4.5 4.5L19 7.5" />
          </svg>
        </div>
        <h1 className="pp-h1" tabIndex={-1} ref={headingRef}>{t('p4.title')}</h1>
        <p className="pp-lead" data-testid="sent-lead">
          {approved ? t(pickupBooking ? 'p4.approvedBooking' : 'p4.approved') : t('p4.review', { store })}
        </p>
      </div>

      <section className="pp-card pp-ref">
        <div className="pp-ref__text">
          <div className="pp-eyebrow">{t('p4.reference')}</div>
          <div className="pp-ref__value" ref={refEl} dir="ltr" data-testid="reference">{result.reference}</div>
        </div>
        <button type="button" className="pp-btn pp-btn--secondary" onClick={copy}>
          {copied ? t('p4.copied') : t('p4.copy')}
        </button>
      </section>
      {manual && <p className="pp-muted pp-small" role="status">{t('p4.copyManual')}</p>}

      <section className="pp-next">
        <h2 className="pp-h3">{t('p4.nextTitle')}</h2>
        <ol className="pp-steps">
          {steps.map((text, i) => (
            <li key={i}>
              <span className={'pp-steps__num' + (i === 0 ? ' pp-steps__num--active' : '')}>{i + 1}</span>
              <span className="pp-steps__text">{text}</span>
            </li>
          ))}
        </ol>
      </section>

      {email && (
        <p className="pp-muted pp-small" data-testid="sent-email">
          {t('p4.email', { email: '⁦' + maskEmail(email) + '⁩' })}
        </p>
      )}
    </>
  )
}
