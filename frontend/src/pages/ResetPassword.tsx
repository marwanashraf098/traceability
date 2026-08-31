import { useState, FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { resetPassword } from '../api'
import AuthLayout from '../components/AuthLayout'
import { Input, Button } from '../components/ui'

export default function ResetPassword() {
  const { t }      = useTranslation()
  const navigate   = useNavigate()
  const location   = useLocation()
  // Prefilled when arriving from ForgotPassword's "Enter code" button — still a plain,
  // editable field, never assumed to be the only way a user lands on this screen.
  const prefilledEmail = (location.state as { email?: string } | null)?.email ?? ''

  const [email,        setEmail]        = useState(prefilledEmail)
  const [code,         setCode]         = useState('')
  const [password,     setPassword]     = useState('')
  const [confirm,      setConfirm]      = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error,        setError]        = useState('')
  const [loading,      setLoading]      = useState(false)

  function validate(): string {
    if (password.length < 8) return t('resetPassword.errors.passwordShort')
    if (password !== confirm) return t('resetPassword.errors.mismatch')
    return ''
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const validationErr = validate()
    if (validationErr) { setError(validationErr); return }

    setLoading(true)
    try {
      await resetPassword(email.trim(), code.trim(), password)
      navigate('/login', { state: { resetSuccess: true } })
    } catch {
      // Backend never distinguishes bad email / bad code / expired / locked — same
      // generic message here, so the UI can't leak a sub-condition either.
      setError(t('resetPassword.error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout>
      <div className="card p-6">
        <div className="mb-5">
          <h2 className="text-h3 text-primary">{t('resetPassword.cardTitle')}</h2>
          <p className="text-small text-muted mt-1">{t('resetPassword.cardSubtitle')}</p>
        </div>

        {error && (
          <div role="alert" className="text-small text-critical bg-critical/10 border border-critical/25 rounded-lg px-3 py-2 mb-4">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="email">
              {t('resetPassword.email')}
            </label>
            <Input
              id="email"
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              autoComplete="email"
              autoFocus={!prefilledEmail}
              invalid={!!error}
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="code">
              {t('resetPassword.code')}
            </label>
            <Input
              id="code"
              type="text"
              required
              value={code}
              onChange={e => setCode(e.target.value.replace(/\s+/g, ''))}
              inputMode="numeric"
              maxLength={6}
              autoComplete="one-time-code"
              autoFocus={!!prefilledEmail}
              dir="ltr"
              invalid={!!error}
            />
            <p className="text-caption text-muted">{t('resetPassword.codeHint')}</p>
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="password">
              {t('resetPassword.newPassword')}
            </label>
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              required
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="new-password"
              minLength={8}
              endAdornment={
                <button
                  type="button"
                  onClick={() => setShowPassword(v => !v)}
                  className="text-caption font-medium text-muted hover:text-primary transition-colors px-2 py-1"
                >
                  {showPassword ? t('common.hidePassword') : t('common.showPassword')}
                </button>
              }
            />
            <p className="text-caption text-muted">{t('resetPassword.passwordHint')}</p>
          </div>

          <div className="space-y-1.5">
            <label className="block text-small text-muted" htmlFor="confirmPassword">
              {t('resetPassword.confirmPassword')}
            </label>
            <Input
              id="confirmPassword"
              type={showPassword ? 'text' : 'password'}
              required
              value={confirm}
              onChange={e => setConfirm(e.target.value)}
              autoComplete="new-password"
            />
          </div>

          <Button
            type="submit"
            variant="primary"
            loading={loading}
            disabled={loading || !email.trim() || !code.trim() || !password || !confirm}
            className="w-full"
          >
            {t('resetPassword.submit')}
          </Button>
        </form>
      </div>

      <p className="text-center text-small text-muted mt-5">
        <Link to="/login" className="text-trace-blue hover:underline transition-colors">
          {t('resetPassword.backToLogin')}
        </Link>
      </p>
    </AuthLayout>
  )
}
