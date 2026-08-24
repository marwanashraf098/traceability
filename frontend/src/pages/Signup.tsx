import { useState, FormEvent, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { signup } from '../api'
import { setAccessToken } from '../auth'
import AuthLayout from '../components/AuthLayout'
import { Input, Button, Checkbox } from '../components/ui'

// Local Egyptian mobile subscriber number, entered after the fixed "+20" prefix
// (no leading zero, e.g. "1012345678").
const EGYPT_LOCAL_MOBILE = /^1[0-9]{9}$/

/** Strips whitespace and a leading zero so "010 1234 5678" and "1012345678" both compose the same +20 number. */
function toE164(localInput: string): string {
  const digits = localInput.replace(/\s+/g, '').replace(/^0+/, '')
  return `+20${digits}`
}

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

  const phoneDigits = phone.replace(/\s+/g, '').replace(/^0+/, '')
  const phoneValid = EGYPT_LOCAL_MOBILE.test(phoneDigits)

  function validate(): string {
    if (password.length < 8) return t('signup.errors.passwordShort')
    if (!phoneValid) return t('signup.errors.phoneInvalid')
    return ''
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const validationErr = validate()
    if (validationErr) { setError(validationErr); return }

    setLoading(true)
    try {
      const res = await signup(
        businessName.trim(), ownerName.trim(), email.trim(), toE164(phone), password, consent
      )
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
    <AuthLayout>
      <div className="card p-6">
        <div className="mb-5">
          <h2 className="text-h3 text-primary">{t('signup.title')}</h2>
          <p className="text-small text-muted mt-1">{t('signup.subtitle')}</p>
        </div>

        {error && (
          <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="businessName">
              {t('signup.businessName')}
            </label>
            <Input
              id="businessName"
              type="text"
              required
              value={businessName}
              onChange={e => setBusinessName(e.target.value)}
              autoComplete="organization"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="ownerName">
              {t('signup.ownerName')}
            </label>
            <Input
              id="ownerName"
              type="text"
              required
              value={ownerName}
              onChange={e => setOwnerName(e.target.value)}
              autoComplete="name"
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="email">
              {t('signup.email')}
            </label>
            <Input
              id="email"
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              autoComplete="email"
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="phone">
              {t('signup.phone')}
            </label>
            <Input
              id="phone"
              type="tel"
              required
              prefix="+20"
              value={phone}
              onChange={e => setPhone(e.target.value)}
              placeholder={t('signup.phonePlaceholder')}
              autoComplete="tel-national"
              inputMode="numeric"
              dir="ltr"
              invalid={!!error && !phoneValid}
            />
            <p className="text-caption text-muted">{t('signup.phoneHint')}</p>
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="password">
              {t('signup.password')}
            </label>
            <Input
              id="password"
              type="password"
              required
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="new-password"
              minLength={8}
            />
            <p className="text-caption text-muted">{t('signup.passwordHint')}</p>
          </div>

          <Checkbox
            checked={consent}
            onChange={setConsent}
            required
            label={
              <>
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
              </>
            }
          />

          <Button
            type="submit"
            variant="primary"
            loading={loading}
            disabled={
              loading || !businessName.trim() || !ownerName.trim() || !email.trim() ||
              !phoneValid || !password || !consent
            }
            className="w-full"
          >
            {t('signup.submit')}
          </Button>
        </form>
      </div>

      <p className="text-center text-small text-muted mt-5">
        {t('signup.haveAccount')}{' '}
        <Link to="/login" className="text-brand hover:text-brand-hover transition-colors">
          {t('signup.signIn')}
        </Link>
      </p>
    </AuthLayout>
  )
}
