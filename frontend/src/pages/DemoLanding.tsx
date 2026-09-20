import { useState, FormEvent, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { demoStart, DemoStartError } from '../api'
import { setAccessToken } from '../auth'
import AuthLayout from '../components/AuthLayout'
import { Modal, Input, Button, Checkbox } from '../components/ui'

/** Set the moment a demo session starts; cleared on session loss or a real login.
 *  See RequireAuth/RootRoute in App.tsx for the redirect this marker drives. */
export const DEMO_SESSION_MARKER = 'traced_demo'

/**
 * FR-DEMO Day 3 — public /demo landing. Two states, driven by the ?expired=1 query
 * param (set by RequireAuth/RootRoute when a demo session is lost — see App.tsx):
 *   - no param: the "start a live demo" card, with the modal auto-opened on mount.
 *   - ?expired=1: "your demo session ended" card instead — closing/reopening the
 *     modal from here starts a genuinely NEW demo session, same form either way.
 *
 * Reuses AuthLayout (Login/Signup's shared public-page frame) and DS ui.tsx
 * primitives only (Modal/Input/Button/Checkbox) — no hand-rolled UI.
 */
export default function DemoLanding() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const expired = params.get('expired') === '1'
  const isAr = i18n.language === 'ar'

  // Not expired -> open the modal immediately on first render (no flash of the
  // base card first). Expired -> base "session ended" card, modal stays closed
  // until "Start demo" is clicked.
  const [showModal, setShowModal] = useState(() => !expired)

  useEffect(() => {
    // A visitor who lands here via the expired redirect is no longer "in" a demo
    // session — clear the marker so a later, unrelated logged-out hit (e.g. they
    // click through to /login on their own) goes to the plain login form instead
    // of looping back here.
    if (expired) sessionStorage.removeItem(DEMO_SESSION_MARKER)
  }, [expired])

  return (
    <AuthLayout>
      <div className="card p-6 text-center">
        {expired ? (
          <>
            <h2 className="text-h3 text-primary">{t('demo.expired.title')}</h2>
            <p className="text-small text-muted mt-2">{t('demo.expired.message')}</p>
            <Button
              variant="primary"
              className="w-full mt-5"
              onClick={() => setShowModal(true)}
            >
              {t('demo.expired.startAgain')}
            </Button>
          </>
        ) : (
          <>
            <h2 className="text-h3 text-primary">{t('demo.landing.title')}</h2>
            <p className="text-small text-muted mt-2">{t('demo.landing.subtitle')}</p>
          </>
        )}
      </div>

      {showModal && (
        <DemoModal
          isAr={isAr}
          onClose={() => setShowModal(false)}
          onSuccess={(accessToken, redirect) => {
            setAccessToken(accessToken)
            sessionStorage.setItem(DEMO_SESSION_MARKER, '1')
            navigate(redirect)
          }}
        />
      )}
    </AuthLayout>
  )
}

function DemoModal({
  isAr,
  onClose,
  onSuccess,
}: {
  isAr: boolean
  onClose: () => void
  onSuccess: (accessToken: string, redirect: string) => void
}) {
  const { t } = useTranslation()

  const [name,    setName]    = useState('')
  const [email,   setEmail]   = useState('')
  const [phone,   setPhone]   = useState('')
  const [consent, setConsent] = useState(false)
  const [error,   setError]   = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await demoStart(name.trim(), email.trim(), phone.trim(), consent)
      onSuccess(res.accessToken, res.redirect)
    } catch (err: unknown) {
      if (err instanceof DemoStartError) {
        setError(isAr ? err.messageAr : err.messageEn)
      } else {
        setError(t('demo.errors.generic'))
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal title={t('demo.modal.title')} onClose={onClose}>
      {error && (
        <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div className="space-y-1.5">
          <label className="block text-small text-muted" htmlFor="demo-name">
            {t('demo.modal.name')}
          </label>
          <Input
            id="demo-name"
            type="text"
            required
            value={name}
            onChange={e => setName(e.target.value)}
            autoComplete="name"
            autoFocus
          />
        </div>

        <div className="space-y-1.5">
          <label className="block text-small text-muted" htmlFor="demo-email">
            {t('demo.modal.email')}
          </label>
          <Input
            id="demo-email"
            type="email"
            required
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoComplete="email"
          />
        </div>

        <div className="space-y-1.5">
          <label className="block text-small text-muted" htmlFor="demo-phone">
            {t('demo.modal.phone')}
          </label>
          <Input
            id="demo-phone"
            type="tel"
            required
            value={phone}
            onChange={e => setPhone(e.target.value)}
            autoComplete="tel"
          />
        </div>

        <Checkbox
          checked={consent}
          onChange={setConsent}
          required
          label={
            <>
              {t('demo.consent.prefix')}{' '}
              <a
                href="/privacy"
                target="_blank"
                rel="noopener noreferrer"
                className="text-brand hover:text-brand-hover underline underline-offset-2 transition-colors"
              >
                {t('demo.consent.privacy')}
              </a>
              {' '}{t('demo.consent.and')}{' '}
              <a
                href="/terms"
                target="_blank"
                rel="noopener noreferrer"
                className="text-brand hover:text-brand-hover underline underline-offset-2 transition-colors"
              >
                {t('demo.consent.terms')}
              </a>
            </>
          }
        />

        <Button
          type="submit"
          variant="primary"
          loading={loading}
          disabled={loading || !name.trim() || !email.trim() || !phone.trim() || !consent}
          className="w-full"
        >
          {t('demo.modal.submit')}
        </Button>
      </form>
    </Modal>
  )
}
