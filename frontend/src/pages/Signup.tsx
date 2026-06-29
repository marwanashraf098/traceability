import { useState, FormEvent, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { signup } from '../api'
import { setAccessToken } from '../auth'
import { Logo } from '../components/Logo'

const EGYPT_PHONE = /^(\+20|0020|0)1[0-9]{9}$/

export default function Signup() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const [businessName, setBusinessName] = useState('')
  const [ownerName,    setOwnerName]    = useState('')
  const [email,        setEmail]        = useState('')
  const [phone,        setPhone]        = useState('')
  const [password,     setPassword]     = useState('')
  const [consent,      setConsent]      = useState(false)
  const [error,        setError]        = useState('')
  const [loading,      setLoading]      = useState(false)

  useEffect(() => { localStorage.removeItem('token') }, [])

  function validate(): string {
    if (password.length < 8) return t('signup.errors.passwordShort')
    if (phone && !EGYPT_PHONE.test(phone.trim())) return t('signup.errors.phoneInvalid')
    return ''
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const validationErr = validate()
    if (validationErr) { setError(validationErr); return }

    setLoading(true)
    try {
      const res = await signup(businessName.trim(), ownerName.trim(), email.trim(), password, consent)
      setAccessToken(res.accessToken)
      navigate('/overview')
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : ''
      if (msg.includes('409') || msg.toLowerCase().includes('conflict')) {
        setError(t('signup.errors.emailTaken'))
      } else {
        setError(t('signup.errors.generic'))
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-base flex items-center justify-center px-4 py-12">
      {/* Background glow — matches Login */}
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-brand/5 blur-3xl" />
      </div>

      <div className="w-full max-w-sm relative z-10">
        {/* Wordmark */}
        <div className="text-center mb-10">
          <Logo variant="icon" size={56} className="mb-5" />
          <h1 className="text-display font-light text-primary tracking-tight">
            <span className="text-brand">tr</span>aced
          </h1>
          <p className="text-small text-muted mt-2 tracking-wide">{t('signup.subtitle')}</p>
        </div>

        {/* Form card */}
        <div className="card p-6 space-y-4">
          <h2 className="text-h3 text-primary">{t('signup.title')}</h2>

          {error && (
            <div role="alert" className="text-small text-danger bg-danger/10 border border-danger/25 rounded px-3 py-2">
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="businessName">
                {t('signup.businessName')}
              </label>
              <input
                id="businessName"
                type="text"
                required
                value={businessName}
                onChange={e => setBusinessName(e.target.value)}
                className="input"
                autoComplete="organization"
                autoFocus
              />
            </div>

            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="ownerName">
                {t('signup.ownerName')}
              </label>
              <input
                id="ownerName"
                type="text"
                required
                value={ownerName}
                onChange={e => setOwnerName(e.target.value)}
                className="input"
                autoComplete="name"
              />
            </div>

            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="email">
                {t('signup.email')}
              </label>
              <input
                id="email"
                type="email"
                required
                value={email}
                onChange={e => setEmail(e.target.value)}
                className="input"
                autoComplete="email"
              />
            </div>

            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="phone">
                {t('signup.phone')}
              </label>
              <input
                id="phone"
                type="tel"
                value={phone}
                onChange={e => setPhone(e.target.value)}
                className="input"
                placeholder={t('signup.phonePlaceholder')}
                autoComplete="tel"
                inputMode="tel"
                dir="ltr"
              />
            </div>

            <div>
              <label className="block text-small text-muted mb-1.5" htmlFor="password">
                {t('signup.password')}
              </label>
              <input
                id="password"
                type="password"
                required
                value={password}
                onChange={e => setPassword(e.target.value)}
                className="input"
                autoComplete="new-password"
                minLength={8}
              />
              <p className="text-caption text-muted mt-1">{t('signup.passwordHint')}</p>
            </div>

            {/* Consent checkbox — required; button stays disabled until ticked */}
            <div className="flex items-start gap-3 pt-1">
              <input
                id="consent"
                type="checkbox"
                checked={consent}
                onChange={e => setConsent(e.target.checked)}
                className="mt-0.5 h-4 w-4 rounded border-line bg-panel accent-brand cursor-pointer shrink-0"
                aria-required="true"
              />
              <label htmlFor="consent" className="text-small text-muted leading-snug cursor-pointer select-none">
                {t('signup.consent.prefix')}{' '}
                <a
                  href="/privacy"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-brand hover:text-brand-hover underline underline-offset-2 transition-colors"
                >
                  {t('signup.consent.privacy')}
                </a>
                {' '}{t('signup.consent.and')}{' '}
                <a
                  href="/terms"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-brand hover:text-brand-hover underline underline-offset-2 transition-colors"
                >
                  {t('signup.consent.terms')}
                </a>
              </label>
            </div>

            <button
              type="submit"
              disabled={loading || !businessName.trim() || !ownerName.trim() || !email.trim() || !password || !consent}
              className="btn-brand btn w-full py-2.5"
            >
              {loading ? t('common.loading') : t('signup.submit')}
            </button>
          </form>
        </div>

        {/* Sign-in link */}
        <p className="text-center text-small text-muted mt-5">
          {t('signup.haveAccount')}{' '}
          <Link to="/login" className="text-brand hover:text-brand-hover transition-colors">
            {t('signup.signIn')}
          </Link>
        </p>
      </div>
    </div>
  )
}
