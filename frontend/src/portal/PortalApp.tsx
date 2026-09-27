import { CSSProperties, FormEvent, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import {
  getConfig, lookup, submit, ExchangeOption, LookupLine, LookupResult, PickupDistrict, PortalConfig, SubmitResult,
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
 *
 * Step 5b (X1–X3): when config.exchangesEnabled, step 1 starts with Return / Exchange. Exchange
 * mode picks ONE item (radio) and its reason, then X2 chooses the new size / colour from the
 * line's exchangeOptions (out of stock disabled, the customer's own variant marked "yours") with a
 * pre-ticked refund-fallback checkbox, then the same P3 as step 3 of 3, then X3. Hidden entirely
 * when the flag is absent. Refund mode is the unchanged P2 → P3 → P4.
 */

export const NOTE_MAX = 300
// Same rule as PortalService.EMAIL on the backend.
export const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

type Step = 'start' | 'items' | 'variant' | 'details' | 'sent'
type Mode = 'refund' | 'exchange'
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

/** "White / M" style option values → "White · M" (the title when no values are known). */
export function optionLabel(options: string[] | undefined, title: string | null): string {
  return options && options.length > 0 ? options.join(' · ') : (title ?? '')
}

/** The exchange option whose option values equal {@code values}, if any. */
export function findOption(line: LookupLine, values: string[]): ExchangeOption | undefined {
  return (line.exchangeOptions ?? []).find(o => o.options.length === values.length && o.options.every((v, i) => v === values[i]))
}

/**
 * X2 starting selection: the customer's own values, with the size axis (else the last axis)
 * left open — the mockup keeps "White" chosen and marks "M · yours" on the size row.
 */
export function initialValues(line: LookupLine): string[] {
  const current = line.currentOptions ?? []
  const axes = line.optionAxes ?? []
  if (current.length === 0) return []
  const sizeAxis = axes.findIndex(a => a.kind === 'size')
  const open = sizeAxis >= 0 ? sizeAxis : current.length - 1
  return current.map((v, i) => (i === open ? '' : v))
}

const SIZE_ORDER = ['xxs', 'xs', 's', 'small', 'm', 'medium', 'l', 'large', 'xl', 'xxl', '2xl', 'xxxl', '3xl', '4xl', '5xl']

/** Sizes in wearing order (XS < S < M < L < XL…), numbers by value, anything else last. */
export function sizeRank(value: string): number {
  const v = value.trim().toLowerCase()
  const n = Number(v)
  if (v !== '' && !Number.isNaN(n)) return 1000 + n
  const i = SIZE_ORDER.indexOf(v)
  return i >= 0 ? i : 100000
}

/** Options matching every chosen ('' = open) value. */
export function matchingOptions(line: LookupLine, values: string[]): ExchangeOption[] {
  return (line.exchangeOptions ?? []).filter(o => values.every((v, i) => v === '' || o.options[i] === v))
}

/** Lines the customer could exchange: returnable, with at least one sibling variant. */
export function exchangeable(line: LookupLine): boolean {
  return !line.nonReturnable && line.returnableQuantity > 0 && (line.exchangeOptions?.length ?? 0) > 0
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

  // Step 5b — exchange mode.
  const [mode, setMode] = useState<Mode>('refund')
  const [exchangeLineId, setExchangeLineId] = useState<string | null>(null)
  const [exchangeReason, setExchangeReason] = useState('')
  const [exchangeValues, setExchangeValues] = useState<string[]>([])
  const [exchangeTargetId, setExchangeTargetId] = useState<string | null>(null)
  const [fallbackOk, setFallbackOk] = useState(true)

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
      setMode('refund')
      setExchangeLineId(null)
      setExchangeReason('')
      setExchangeTargetId(null)
      setFallbackOk(true)
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

  // ── X1 / X2 (Step 5b) ─────────────────────────────────────────────────────
  const exchangesOn = config?.exchangesEnabled === true
  const exchangeMode = exchangesOn && mode === 'exchange'
  const exchangeLine = order?.lines.find(l => l.variantId === exchangeLineId) ?? null
  const exchangeTarget = exchangeLine?.exchangeOptions?.find(o => o.variantId === exchangeTargetId) ?? null

  function chooseExchangeLine(line: LookupLine) {
    setExchangeLineId(line.variantId)
    setExchangeValues(initialValues(line))
    setExchangeTargetId(null)
  }

  function pickValue(axis: number, value: string) {
    if (!exchangeLine) return
    const next = [...exchangeValues]
    next[axis] = value
    setExchangeValues(next)
    const option = findOption(exchangeLine, next)
    setExchangeTargetId(option && option.inStock ? option.variantId : null)
  }

  // ── P3 ────────────────────────────────────────────────────────────────────
  const pickup = order?.pickup ?? null
  // Step 5c: an exchange courier delivers the new item and collects the old one in one visit, so
  // in exchange mode only districts that allow both are offered.
  const areaDistricts = pickup == null ? []
    : exchangeMode && pickup.exchangeDistrictIds
      ? pickup.districts.filter(d => pickup.exchangeDistrictIds!.includes(d.id))
      : pickup.districts
  const chosenArea = areaDistricts.some(d => d.id === areaId) ? areaId : ''
  const areaMissing = pickup != null && !chosenArea

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
      ...(exchangeMode && exchangeLine && exchangeTarget
        ? {
            mode: 'exchange' as const,
            lines: [{ variantId: exchangeLine.variantId, quantity: 1, reasonCode: exchangeReason }],
            replacementVariantId: exchangeTarget.variantId,
            refundFallbackOk: fallbackOk,
          }
        : {
            lines: selectedLines.map(l => ({
              variantId: l.variantId, quantity: selections[l.variantId].qty, reasonCode: selections[l.variantId].reason,
            })),
          }),
      ...(trimmedEmail ? { email: trimmedEmail } : {}),
      ...(note.trim() ? { note } : {}),
      ...(pickup && chosenArea ? { districtId: chosenArea } : {}),
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

  if (step === 'items' && order && exchangeMode) {
    const canContinueExchange = exchangeLine != null && !!exchangeReason
    return (
      <Shell
        style={rootStyle}
        header={header(() => setStep('start'))}
        footer={(
          <div className="pp-bar">
            <button type="button" className="pp-btn pp-btn--primary pp-btn--block" disabled={!canContinueExchange}
              onClick={() => setStep('variant')}>
              {t('p2.continue')}
            </button>
          </div>
        )}
        hidePowered
      >
        <div className="pp-stephead">
          <div className="pp-eyebrow">{t('p2.step', { step: 1, total: 3 })}</div>
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t('x1.title')}</h1>
          <div className="pp-muted">
            {t('p2.orderLine', { number: '⁨' + order.orderNumber + '⁩', date: shortDate(order.deliveredAt, lang) })}
          </div>
        </div>
        <ModeChoice mode={mode} onChange={setMode} />
        <fieldset className="pp-fieldset">
          <legend className="pp-label pp-legend">{t('x1.whichItem')}</legend>
          {order.lines.map(line => {
            const can = exchangeable(line)
            const selected = exchangeLineId === line.variantId
            if (!can) {
              return (
                <div key={line.variantId} className="pp-item pp-item--disabled" data-testid="exchange-line">
                  <Thumb src={line.imageUrl} muted />
                  <div className="pp-item__text">
                    <div className="pp-item__title"><bdi>{line.productTitle}</bdi></div>
                    <div className="pp-item__sub">
                      {line.variantTitle && <><bdi>{optionLabel(line.currentOptions, line.variantTitle)}</bdi> · </>}
                      {line.nonReturnable ? t('p2.cantReturn')
                        : line.returnableQuantity <= 0 ? t('p2.alreadyRequested') : t('x1.cantExchange')}
                    </div>
                  </div>
                </div>
              )
            }
            return (
              <div key={line.variantId} className={'pp-item' + (selected ? ' pp-item--selected' : '')} data-testid="exchange-line">
                <label className="pp-radioitem">
                  <input type="radio" name="pp-exchange-item" checked={selected} onChange={() => chooseExchangeLine(line)} />
                  <Thumb src={line.imageUrl} />
                  <span className="pp-item__text">
                    <span className="pp-item__title"><bdi>{line.productTitle}</bdi></span>
                    <span className="pp-item__sub"><bdi>{optionLabel(line.currentOptions, line.variantTitle)}</bdi></span>
                  </span>
                </label>
                {selected && (
                  <div className="pp-field">
                    <label htmlFor="pp-exchange-reason" className="pp-label">{t('x1.reasonLabel')}</label>
                    <select
                      id="pp-exchange-reason" className="pp-input" required aria-invalid={!exchangeReason}
                      value={exchangeReason} onChange={e => setExchangeReason(e.target.value)}
                    >
                      <option value="" disabled>{t('p2.reasonPlaceholder')}</option>
                      {config.reasonCodes.map(code => (
                        <option key={code} value={code}>{t(`reasons.${code}`, { defaultValue: code })}</option>
                      ))}
                    </select>
                  </div>
                )}
              </div>
            )
          })}
        </fieldset>
        <p className="pp-muted pp-small">{t('x1.oneItem')}</p>
      </Shell>
    )
  }

  if (step === 'variant' && order && exchangeMode && exchangeLine) {
    return (
      <Shell
        style={rootStyle}
        header={header(() => setStep('items'))}
        footer={(
          <div className="pp-bar">
            <button type="button" className="pp-btn pp-btn--primary pp-btn--block" disabled={!exchangeTarget}
              onClick={() => setStep('details')}>
              {t('p2.continue')}
            </button>
          </div>
        )}
        hidePowered
      >
        <div className="pp-stephead">
          <div className="pp-eyebrow">{t('p2.step', { step: 2, total: 3 })}</div>
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t('x2.title')}</h1>
        </div>
        <section className="pp-card" data-testid="exchange-have">
          <div className="pp-item__head">
            <Thumb src={exchangeLine.imageUrl} small />
            <div className="pp-item__text">
              <div className="pp-item__title"><bdi>{exchangeLine.productTitle}</bdi></div>
              <div className="pp-item__sub">
                {t('x2.youHave', { variant: '' })}<bdi>{optionLabel(exchangeLine.currentOptions, exchangeLine.variantTitle)}</bdi>
              </div>
            </div>
          </div>
        </section>
        <VariantPicker line={exchangeLine} values={exchangeValues} targetId={exchangeTargetId}
          onPickValue={pickValue} onPickOption={o => setExchangeTargetId(o.variantId)} />
        {exchangeTarget && (
          <section className="pp-card" data-testid="exchange-summary">
            <div className="pp-item__title pp-item__title--sm">
              <bdi>{exchangeLine.productTitle} · {optionLabel(exchangeTarget.options, exchangeTarget.title)}</bdi>
            </div>
            <div className="pp-success-text pp-small">{t('x2.inStockSamePrice')}</div>
          </section>
        )}
        <label className="pp-check">
          <input type="checkbox" checked={fallbackOk} onChange={e => setFallbackOk(e.target.checked)} />
          <span>{t('x2.fallback')}</span>
        </label>
      </Shell>
    )
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
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t(exchangesOn ? 'x1.title' : 'p2.title')}</h1>
          <div className="pp-muted">
            {t('p2.orderLine', { number: '⁨' + order.orderNumber + '⁩', date: shortDate(order.deliveredAt, lang) })}
          </div>
        </div>
        {exchangesOn && <ModeChoice mode={mode} onChange={setMode} />}
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
      <Shell style={rootStyle} header={header(() => setStep(exchangeMode ? 'variant' : 'items'))}>
        <div className="pp-stephead">
          <div className="pp-eyebrow">{exchangeMode ? t('p2.step', { step: 3, total: 3 }) : t('p2.step', { step: 2, total: 2 })}</div>
          <h1 className="pp-h2" tabIndex={-1} ref={headingRef}>{t('p3.title')}</h1>
        </div>

        {exchangeMode && exchangeLine && exchangeTarget ? (
          <section className="pp-card">
            <div className="pp-card__head">
              <div className="pp-eyebrow">{t('x3.exchanging')}</div>
              <button type="button" className="pp-link" onClick={() => setStep('variant')}>{t('p3.edit')}</button>
            </div>
            <div className="pp-item__head" data-testid="summary-line">
              <Thumb src={exchangeLine.imageUrl} small />
              <div className="pp-item__text">
                <div className="pp-item__title pp-item__title--sm">
                  <bdi>{exchangeLine.productTitle} · {optionLabel(exchangeLine.currentOptions, exchangeLine.variantTitle)}</bdi>
                  {' → '}<bdi>{optionLabel(exchangeTarget.options, exchangeTarget.title)}</bdi>
                </div>
                <div className="pp-item__sub">{t(`reasons.${exchangeReason}`)}</div>
              </div>
            </div>
          </section>
        ) : (
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
        )}

        <section className="pp-card">
          <div className="pp-eyebrow">{t('p3.pickup')}</div>
          <p className="pp-muted pp-small">
            {t(exchangeMode ? 'p3.pickupTextExchange' : config.pickupBooking || pickup ? 'p3.pickupTextBooking' : 'p3.pickupText')}
          </p>
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
                  value={chosenArea}
                  onChange={e => setAreaId(e.target.value)}
                >
                  <option value="" disabled>{t('p3.areaPlaceholder')}</option>
                  {groupByZone(areaDistricts, lang).map(g => {
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
          exchange={exchangeMode && exchangeLine && exchangeTarget ? {
            product: exchangeLine.productTitle,
            from: optionLabel(exchangeLine.currentOptions, exchangeLine.variantTitle),
            to: optionLabel(exchangeTarget.options, exchangeTarget.title),
          } : null}
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

/** X1 — Return (get a refund) or Exchange (another size or colour). */
function ModeChoice({ mode, onChange }: { mode: Mode; onChange: (m: Mode) => void }) {
  const { t } = useTranslation()
  return (
    <fieldset className="pp-modes">
      <legend className="pp-visually-hidden">{t('x1.modeLegend')}</legend>
      {(['refund', 'exchange'] as Mode[]).map(m => (
        <label key={m} className={'pp-mode' + (mode === m ? ' pp-mode--selected' : '')}>
          <input type="radio" name="pp-mode" checked={mode === m} onChange={() => onChange(m)} />
          <span>
            <span className="pp-mode__title">{t(m === 'refund' ? 'x1.return' : 'x1.exchange')}</span>
            <span className="pp-mode__sub">{t(m === 'refund' ? 'x1.returnSub' : 'x1.exchangeSub')}</span>
          </span>
        </label>
      ))}
    </fieldset>
  )
}

/**
 * X2 — one chip group per option axis. A value is offered when the resulting combination is
 * another variant of the product; out of stock → disabled "· out of stock"; the customer's own
 * combination → disabled "· yours"; no such variant → not shown. Without parsed options, the
 * other variants are listed by title.
 */
function VariantPicker({
  line, values, targetId, onPickValue, onPickOption,
}: {
  line: LookupLine
  values: string[]
  targetId: string | null
  onPickValue: (axis: number, value: string) => void
  onPickOption: (o: ExchangeOption) => void
}) {
  const { t } = useTranslation()
  const axes = line.optionAxes ?? []
  const current = line.currentOptions ?? []
  const options = line.exchangeOptions ?? []
  const usable = axes.length > 0 && current.length === axes.length && options.every(o => o.options.length === axes.length)

  if (!usable) {
    return (
      <fieldset className="pp-fieldset">
        <legend className="pp-label pp-legend">{t('x2.newVariant')}</legend>
        <div className="pp-chips">
          {options.map(o => (
            <label key={o.variantId} className={'pp-chip' + (targetId === o.variantId ? ' pp-chip--selected' : '') + (o.inStock ? '' : ' pp-chip--disabled')}>
              <input type="radio" name="pp-variant" disabled={!o.inStock} checked={targetId === o.variantId}
                onChange={() => onPickOption(o)} />
              <span><bdi>{o.title}</bdi>{!o.inStock && ` · ${t('x2.outOfStock')}`}</span>
            </label>
          ))}
        </div>
      </fieldset>
    )
  }

  return (
    <>
      {axes.map((axis, i) => {
        const valuesOnAxis: string[] = []
        for (const v of [current, ...options.map(o => o.options)]) {
          if (!valuesOnAxis.includes(v[i])) valuesOnAxis.push(v[i])
        }
        if (axis.kind === 'size') valuesOnAxis.sort((a, b) => sizeRank(a) - sizeRank(b))
        const label = axis.kind === 'colour' ? t('x2.colour') : axis.kind === 'size' ? t('x2.size') : (axis.name ?? t('x2.option'))
        return (
          <fieldset key={i} className="pp-fieldset">
            <legend className="pp-label pp-legend">{label}</legend>
            <div className="pp-chips">
              {valuesOnAxis.map(value => {
                const candidate = values.map((v, j) => (j === i ? value : v))
                const isCurrent = candidate.every((v, j) => v === current[j])
                const matches = isCurrent ? [] : matchingOptions(line, candidate)
                const disabled = isCurrent || !matches.some(o => o.inStock)
                const soldOut = !isCurrent && matches.length > 0 && disabled
                const chosen = values[i] === value
                return (
                  <label key={value} className={'pp-chip' + (chosen && !disabled ? ' pp-chip--selected' : '')
                    + (disabled ? ' pp-chip--disabled' : '')}>
                    <input type="radio" name={`pp-axis-${i}`} disabled={disabled}
                      checked={chosen && !disabled} onChange={() => onPickValue(i, value)} />
                    <span>
                      <bdi>{value}</bdi>
                      {isCurrent && ` · ${t('x2.yours')}`}
                      {soldOut && ` · ${t('x2.outOfStock')}`}
                    </span>
                  </label>
                )
              })}
            </div>
          </fieldset>
        )
      })}
    </>
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
  headingRef, store, result, email, pickupBooking, exchange,
}: {
  headingRef: React.RefObject<HTMLHeadingElement>
  store: string
  result: SubmitResult
  email: string | null
  pickupBooking: boolean
  exchange: { product: string; from: string; to: string } | null
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

  const steps = exchange
    ? [t('x3.stepReview', { store }), t('x3.stepCourier'), t('x3.stepReady')]
    : [
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
        <h1 className="pp-h1" tabIndex={-1} ref={headingRef}>{t(exchange ? 'x3.title' : 'p4.title')}</h1>
        {exchange ? (
          <p className="pp-lead" data-testid="sent-lead">
            <bdi>{exchange.product} · {exchange.from}</bdi>{' → '}<strong><bdi>{exchange.to}</bdi></strong>
          </p>
        ) : (
          <p className="pp-lead" data-testid="sent-lead">
            {approved ? t(pickupBooking ? 'p4.approvedBooking' : 'p4.approved') : t('p4.review', { store })}
          </p>
        )}
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
