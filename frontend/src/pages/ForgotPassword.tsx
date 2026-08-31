import { useState, FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { forgotPassword } from '../api'
import AuthLayout from '../components/AuthLayout'
import { Input, Button } from '../components/ui'

export default function ForgotPassword() {
  const { t }    = useTranslation()
  const navigate = useNavigate()
  const [email,     setEmail]     = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [error,     setError]     = useState('')
  const [loading,   setLoading]   = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      // Always resolves — the backend gives the same response whether or not the
      // email matched an account. Success here just means "the request went through."
      await forgotPassword(email.trim())
      setSubmitted(true)
    } catch {
      setError(t('forgotPassword.error'))
    } finally {
      setLoading(false)
    }
  }

  if (submitted) {
    return (
      <AuthLayout>
        <div className="card p-6 text-center">
          <h2 className="text-h3 text-primary mb-2">{t('forgotPassword.sentTitle')}</h2>
          <p className="text-small text-muted mb-5">{t('forgotPassword.sentBody')}</p>
          <Button
            type="button"
            variant="primary"
            className="w-full"
            onClick={() => navigate('/reset-password', { state: { email: email.trim() } })}
          >
            {t('forgotPassword.enterCode')}
          </Button>
        </div>
        <p className="text-center text-small text-muted mt-5">
          <Link to="/login" className="text-trace-blue hover:underline transition-colors">
            {t('forgotPassword.backToLogin')}
          </Link>
        </p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout>
      <div className="card p-6">
        <div className="mb-5">
          <h2 className="text-h3 text-primary">{t('forgotPassword.cardTitle')}</h2>
          <p className="text-small text-muted mt-1">{t('forgotPassword.cardSubtitle')}</p>
        </div>

        {error && (
          <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="email">
              {t('forgotPassword.email')}
            </label>
            <Input
              id="email"
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              autoComplete="email"
              autoFocus
              invalid={!!error}
            />
          </div>

          <Button
            type="submit"
            variant="primary"
            loading={loading}
            disabled={loading || !email.trim()}
            className="w-full"
          >
            {t('forgotPassword.submit')}
          </Button>
        </form>
      </div>

      <p className="text-center text-small text-muted mt-5">
        <Link to="/login" className="text-trace-blue hover:underline transition-colors">
          {t('forgotPassword.backToLogin')}
        </Link>
      </p>
    </AuthLayout>
  )
}
