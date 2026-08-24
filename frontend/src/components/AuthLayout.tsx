import { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Logo } from './Logo'

/**
 * Shared public-page frame for Login and Signup: brand glow, centered card
 * slot, brandmark, and legal footer. Each screen supplies only its card body.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation()

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center px-4 py-12">
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-trace-blue/5 blur-3xl" />
      </div>

      <div className="w-full max-w-sm relative z-10">
        <div className="text-center mb-10">
          <Logo variant="wordmark" size={32} className="justify-center mb-4" />
          <p className="text-small text-muted tracking-wide">{t('auth.tagline')}</p>
        </div>

        {children}

        <p className="text-center text-caption text-muted mt-6 leading-relaxed">
          {t('auth.legalPrefix')}{' '}
          <Link to="/terms" className="text-muted hover:text-primary underline underline-offset-2 transition-colors">
            {t('auth.legalTerms')}
          </Link>
          {' '}{t('auth.legalAnd')}{' '}
          <Link to="/privacy" className="text-muted hover:text-primary underline underline-offset-2 transition-colors">
            {t('auth.legalPrivacy')}
          </Link>
          .
        </p>
      </div>
    </div>
  )
}
