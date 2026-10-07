/**
 * Build D — onboarding inside the embedded Shopify app, for a store no Traced account owns yet
 * (design/Traced_embedded_onboarding_dc.html; Polaris, like the rest of the embedded app).
 *
 *   welcome (O1)  → "Create my Traced account" → signup (S1, S2 field errors, S3 email taken)
 *                 → "I already have a Traced account" → existing (L1): the server parks a pending
 *                   link and the TOP-LEVEL window goes to Traced to sign in and confirm (no pop-up,
 *                   no typed store address); Traced sends the browser back here with
 *                   ?traced_connected=1, which shows Connected (C1).
 *   connected (C1) — after signup: the one-time "Open Traced" sign-in link (new tab, not essential).
 *   X1 store linked elsewhere, X2 something went wrong (nothing created).
 *
 * The store is never an input: the server takes it from the verified session token.
 */
import { useEffect, useRef, useState, type FormEvent } from 'react'
import {
  Page, Card, BlockStack, InlineStack, Text, Button, TextField, Checkbox, Banner, Badge, Spinner, Link, Box,
} from '@shopify/polaris'
import { onboardingCopy, type OnboardingCopy } from './onboardingCopy'
import type { EmbeddedLang } from './embeddedLocale'

export const SAAS = 'https://app.tracedtech.com'
const SUPPORT = 'mailto:support@tracedtech.com'

export type AuthFetch = (url: string, options?: RequestInit) => Promise<Response>

type View = 'welcome' | 'signup' | 'existing' | 'linkedElsewhere'

interface Prefill { shopDomain: string; shopName: string | null; email: string | null }

export interface SignupDone { email: string; signInUrl: string; signInValidMinutes: number; issuedAt: number }

interface Form { tenantName: string; name: string; phone: string; email: string; password: string; consent: boolean }

type FieldErrors = Partial<Record<keyof Form, string>>

/** Mirrors AuthService.normalizeEgyptianPhoneToE164's accepted shapes (the server re-checks). */
export function isEgyptianMobile(raw: string): boolean {
  let d = raw.replace(/[^0-9]/g, '')
  if (d.startsWith('0020') && d.length === 14) d = '0' + d.slice(4)
  else if (d.startsWith('20') && d.length === 12) d = '0' + d.slice(2)
  else if (d.length === 10) d = '0' + d
  return d.startsWith('01') && d.length === 11
}

export function validate(f: Form, c: OnboardingCopy): FieldErrors {
  const e: FieldErrors = {}
  if (!f.tenantName.trim()) e.tenantName = c.errBusinessName
  if (!isEgyptianMobile(f.phone)) e.phone = c.errMobile
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(f.email.trim())) e.email = c.errEmail
  if (f.password.length < 8) e.password = c.errPassword
  if (!f.consent) e.consent = c.errConsent
  return e
}

function shopFromUrl(): string {
  const shop = new URLSearchParams(window.location.search).get('shop')?.trim().toLowerCase() ?? ''
  return /^[a-z0-9][a-z0-9-]*\.myshopify\.com$/.test(shop) ? shop : ''
}

async function errorCode(r: Response): Promise<string | null> {
  const body = await r.json().catch(() => null) as { code?: string; error?: string } | null
  return body?.code ?? body?.error ?? null
}

/** Leaves the iframe: navigates the top-level window (App Bridge handles _top). Never a pop-up. */
function navigateTop(url: string) {
  window.open(url, '_top')
}

function ShopChip({ copy, shop, name }: { copy: OnboardingCopy; shop: string; name: string | null }) {
  return (
    <Box background="bg-surface-secondary" borderRadius="300" padding="300">
      <InlineStack align="space-between" blockAlign="center" gap="200">
        <BlockStack gap="050">
          {name && <Text as="p" fontWeight="semibold">{name}</Text>}
          <span dir="ltr" data-testid="onboarding-shop"><Text as="span" tone="subdued">{shop}</Text></span>
        </BlockStack>
        <Badge tone="success">{copy.fromShopify}</Badge>
      </InlineStack>
    </Box>
  )
}

function FailedBanner({ copy, onRetry }: { copy: OnboardingCopy; onRetry?: () => void }) {
  return (
    <Banner tone="critical" title={copy.failedTitle}
            action={onRetry ? { content: copy.tryAgain, onAction: onRetry } : undefined}
            secondaryAction={{ content: copy.contactSupport, url: SUPPORT }}>
      <p>{copy.failedBody}</p>
    </Banner>
  )
}

export default function Onboarding({ lang, authFetch, onSignedUp }: {
  lang: EmbeddedLang
  authFetch: AuthFetch
  /** Called with the signup result; the parent shows Connected (C1). */
  onSignedUp: (done: SignupDone) => void
}) {
  const copy = onboardingCopy[lang]
  const [view, setView] = useState<View>('welcome')
  const [prefill, setPrefill] = useState<Prefill | null>(null)
  const [form, setForm] = useState<Form>({ tenantName: '', name: '', phone: '', email: '', password: '', consent: false })
  const [errors, setErrors] = useState<FieldErrors>({})
  const [banner, setBanner] = useState<null | 'emailTaken' | 'failed' | 'rules' | { message: string }>(null)
  const [busy, setBusy] = useState(false)
  const prefilled = useRef(false)

  useEffect(() => {
    authFetch('/api/v1/embedded/onboarding/prefill')
      .then(async r => {
        if (r.ok) return r.json() as Promise<Prefill>
        if (r.status === 409 && await errorCode(r) === 'SHOP_LINKED_ELSEWHERE') setView('linkedElsewhere')
        return null
      })
      .then(p => {
        if (!p || prefilled.current) return
        prefilled.current = true
        setPrefill(p)
        setForm(f => ({ ...f, tenantName: f.tenantName || p.shopName || '', email: f.email || p.email || '' }))
      })
      .catch(() => { /* prefill is a convenience — the form works without it */ })
  }, [authFetch])

  const shop = prefill?.shopDomain || shopFromUrl()

  function set<K extends keyof Form>(k: K, v: Form[K]) {
    setForm(f => ({ ...f, [k]: v }))
    if (errors[k]) setErrors(e => ({ ...e, [k]: undefined }))
  }

  async function submit(e?: FormEvent) {
    e?.preventDefault()
    setBanner(null)
    const found = validate(form, copy)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      const r = await authFetch('/api/v1/embedded/onboarding/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, tenantName: form.tenantName.trim(), email: form.email.trim() }),
      })
      if (r.ok) {
        const body = await r.json() as { email: string; signInUrl: string; signInValidMinutes: number }
        onSignedUp({ ...body, issuedAt: Date.now() })
        return
      }
      if (r.status === 409) {
        const code = await errorCode(r)
        if (code === 'EMAIL_TAKEN') { setErrors({ email: ' ' }); setBanner('emailTaken'); return }
        if (code === 'SHOP_LINKED_ELSEWHERE') { setView('linkedElsewhere'); return }
      }
      if (r.status === 400 || r.status === 422) { setBanner('rules'); return }
      if (r.status === 429) {
        const body = await r.json().catch(() => null) as { message_en?: string; message_ar?: string } | null
        const msg = lang === 'ar' ? body?.message_ar : body?.message_en
        setBanner(msg ? { message: msg } : 'failed')
        return
      }
      setBanner('failed')
    } catch {
      setBanner('failed')
    } finally {
      setBusy(false)
    }
  }

  async function continueToTraced() {
    setBanner(null)
    setBusy(true)
    try {
      const r = await authFetch('/api/v1/embedded/onboarding/pending-link', { method: 'POST' })
      if (r.ok) {
        const { url } = await r.json() as { url: string }
        navigateTop(url)
        return
      }
      if (r.status === 409 && await errorCode(r) === 'SHOP_LINKED_ELSEWHERE') { setView('linkedElsewhere'); return }
      setBanner('failed')
    } catch {
      setBanner('failed')
    } finally {
      setBusy(false)
    }
  }

  function goto(v: View) { setBanner(null); setView(v) }

  if (view === 'linkedElsewhere') {
    return (
      <Page title={copy.pageTitle} narrowWidth>
        <Card>
          <Banner tone="warning" title={copy.linkedElsewhereTitle}
                  action={{ content: copy.signInToTraced, url: `${SAAS}/login`, external: true }}
                  secondaryAction={{ content: copy.contactSupport, url: SUPPORT }}>
            <p>{copy.linkedElsewhereBody}</p>
          </Banner>
        </Card>
      </Page>
    )
  }

  if (view === 'existing') {
    return (
      <Page title={copy.pageTitle} narrowWidth>
        <Card>
          <BlockStack gap="400">
            <BlockStack gap="100">
              <Text as="h2" variant="headingMd">{copy.existingPageTitle}</Text>
              <Text as="p" tone="subdued">{copy.existingPageBody}</Text>
            </BlockStack>
            {shop && <ShopChip copy={copy} shop={shop} name={prefill?.shopName ?? null} />}
            {banner === 'failed' && <FailedBanner copy={copy} onRetry={() => void continueToTraced()} />}
            <Button variant="primary" fullWidth loading={busy} onClick={() => void continueToTraced()}>
              {copy.continueToTraced}
            </Button>
            <InlineStack><Button variant="plain" onClick={() => goto('welcome')}>{copy.back}</Button></InlineStack>
          </BlockStack>
        </Card>
      </Page>
    )
  }

  if (view === 'signup') {
    return (
      <Page title={copy.pageTitle} narrowWidth>
        <Card>
          <form onSubmit={e => void submit(e)} noValidate>
            <BlockStack gap="400">
              <BlockStack gap="100">
                <Text as="h2" variant="headingMd">{copy.signupTitle}</Text>
                <Text as="p" tone="subdued">{copy.signupBody}</Text>
              </BlockStack>
              {shop && (
                <BlockStack gap="100">
                  <Text as="span" variant="bodySm" tone="subdued">{copy.store}</Text>
                  <ShopChip copy={copy} shop={shop} name={null} />
                </BlockStack>
              )}
              <TextField label={copy.businessName} value={form.tenantName} onChange={v => set('tenantName', v)}
                         autoComplete="organization" error={errors.tenantName} />
              <TextField label={copy.yourName} value={form.name} onChange={v => set('name', v)} autoComplete="name" />
              <TextField label={copy.mobile} value={form.phone} onChange={v => set('phone', v)} type="tel"
                         placeholder={copy.mobilePlaceholder} autoComplete="tel" error={errors.phone} />
              <TextField label={copy.email} value={form.email} onChange={v => set('email', v)} type="email"
                         autoComplete="email" helpText={copy.emailHint}
                         error={errors.email?.trim() ? errors.email : errors.email ? true : undefined} />
              <TextField label={copy.password} value={form.password} onChange={v => set('password', v)}
                         type="password" autoComplete="new-password" helpText={copy.passwordHint} error={errors.password} />
              <Checkbox
                label={<>
                  {copy.consentBefore} <Link url={`${SAAS}/terms`} target="_blank">{copy.terms}</Link>{' '}
                  {copy.consentAnd} <Link url={`${SAAS}/privacy`} target="_blank">{copy.privacy}</Link>.
                </>}
                checked={form.consent} onChange={v => set('consent', v)} error={errors.consent} />
              {banner === 'emailTaken' && (
                <Banner tone="info" title={copy.emailTakenTitle}
                        action={{ content: copy.signInInstead, onAction: () => goto('existing') }}
                        secondaryAction={{ content: copy.useDifferentEmail, onAction: () => { set('email', ''); setBanner(null) } }}>
                  <p>{copy.emailTakenBody}</p>
                </Banner>
              )}
              {banner === 'failed' && <FailedBanner copy={copy} onRetry={() => void submit()} />}
              {banner === 'rules' && <Banner tone="critical" title={copy.errRules} />}
              {typeof banner === 'object' && banner !== null && <Banner tone="critical" title={banner.message} />}
              <InlineStack align="space-between" blockAlign="center" gap="200">
                <Button variant="plain" onClick={() => goto('welcome')} disabled={busy}>{copy.back}</Button>
                <Button variant="primary" submit loading={busy}>{copy.createAndConnect}</Button>
              </InlineStack>
            </BlockStack>
          </form>
        </Card>
      </Page>
    )
  }

  return (
    <Page title={copy.pageTitle} narrowWidth>
      <Card>
        <BlockStack gap="400">
          <BlockStack gap="100">
            <Text as="h2" variant="headingLg">{copy.welcomeTitle}</Text>
            <Text as="p" tone="subdued">{copy.welcomeBody}</Text>
          </BlockStack>
          {shop && <ShopChip copy={copy} shop={shop} name={prefill?.shopName ?? null} />}
          <Box borderColor="border" borderWidth="025" borderRadius="300" padding="400">
            <BlockStack gap="200">
              <Text as="p" fontWeight="semibold">{copy.createTitle}</Text>
              <Text as="p" tone="subdued">{copy.createBody}</Text>
              <InlineStack><Button variant="primary" onClick={() => goto('signup')}>{copy.createTitle}</Button></InlineStack>
            </BlockStack>
          </Box>
          <Box borderColor="border" borderWidth="025" borderRadius="300" padding="400">
            <BlockStack gap="200">
              <Text as="p" fontWeight="semibold">{copy.existingTitle}</Text>
              <Text as="p" tone="subdued">{copy.existingBody}</Text>
              <InlineStack><Button onClick={() => goto('existing')}>{copy.existingTitle}</Button></InlineStack>
            </BlockStack>
          </Box>
        </BlockStack>
      </Card>
    </Page>
  )
}

// ── C1: connected, first import ────────────────────────────────────────────

type ImportState = 'running' | 'completed' | 'failed'

/**
 * Shown after a signup (with the one-time "Open Traced" link) and after a confirmed pending link
 * (?traced_connected=1 — plain "Open Traced", the merchant is already signed in there). The store
 * is linked now, so the normal embedded endpoints answer; the import status is polled until done.
 */
export function Connected({ lang, authFetch, signup, onDashboard }: {
  lang: EmbeddedLang
  authFetch: AuthFetch
  signup: SignupDone | null
  onDashboard: () => void
}) {
  const copy = onboardingCopy[lang]
  const [shop, setShop] = useState(shopFromUrl())
  const [imp, setImp] = useState<ImportState>('running')
  const linkUsed = useRef(false)

  useEffect(() => {
    let stop = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const poll = () => {
      authFetch('/api/v1/embedded/stores/status')
        .then(r => r.ok ? r.json() as Promise<{ shop_domain: string; import_status: string | null }[]> : [])
        .then(rows => {
          if (stop) return
          const row = rows[0]
          if (row?.shop_domain) setShop(row.shop_domain)
          const s = row?.import_status
          if (s === 'completed' || s === 'idle') { setImp('completed'); return }
          if (s === 'failed') { setImp('failed'); return }
          timer = setTimeout(poll, 4000)
        })
        .catch(() => { if (!stop) timer = setTimeout(poll, 8000) })
    }
    poll()
    return () => { stop = true; if (timer) clearTimeout(timer) }
  }, [authFetch])

  /** The one-time link while it is still valid and unused; otherwise Traced's front door. */
  function openTraced() {
    const fresh = signup && !linkUsed.current
      && Date.now() < signup.issuedAt + signup.signInValidMinutes * 60_000 - 30_000
    const url = fresh ? signup!.signInUrl : SAAS
    if (fresh) linkUsed.current = true
    window.open(url, '_blank', 'noopener,noreferrer')
  }

  const step = (state: 'done' | 'run' | 'wait' | 'fail', title: string, sub?: string) => (
    <InlineStack gap="300" blockAlign="start" wrap={false}>
      <Box minWidth="20px">
        {state === 'run' ? <Spinner size="small" />
          : <Badge tone={state === 'done' ? 'success' : state === 'fail' ? 'critical' : undefined}>
              {state === 'done' ? '✓' : state === 'fail' ? '!' : '·'}
            </Badge>}
      </Box>
      <BlockStack gap="050">
        <Text as="p" fontWeight="medium" tone={state === 'wait' ? 'subdued' : undefined}>{title}</Text>
        {sub && <Text as="p" variant="bodySm" tone="subdued">{sub}</Text>}
      </BlockStack>
    </InlineStack>
  )

  return (
    <Page title={copy.pageTitle} narrowWidth>
      <Card>
        <BlockStack gap="500">
          <BlockStack gap="100" inlineAlign="center">
            <Text as="h2" variant="headingLg" alignment="center">{copy.connectedTitle}</Text>
            {shop && (
              <Text as="p" tone="subdued" alignment="center">
                <span dir="ltr">{shop}</span> {copy.connectedBody}
              </Text>
            )}
          </BlockStack>
          <BlockStack gap="300">
            {signup && step('done', copy.stepAccount, `${copy.stepAccountSub} ${signup.email}`)}
            {step('done', copy.stepStore, copy.stepStoreSub)}
            {imp === 'running' && step('run', copy.stepImport, copy.stepImportSub)}
            {imp === 'completed' && step('done', copy.stepImportDone)}
            {imp === 'failed' && step('fail', copy.stepImportFailed)}
            {step('wait', copy.stepNext, copy.stepNextSub)}
          </BlockStack>
          <Button variant="primary" fullWidth onClick={openTraced}>{copy.openTraced}</Button>
          <InlineStack align="center"><Button variant="plain" onClick={onDashboard}>{copy.toDashboard}</Button></InlineStack>
        </BlockStack>
      </Card>
    </Page>
  )
}
