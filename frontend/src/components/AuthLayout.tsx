import { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Globe } from 'lucide-react'
import { Logo } from './Logo'

function toggleLang(i18n: { language: string; changeLanguage: (lng: string) => void }) {
  const next = i18n.language === 'en' ? 'ar' : 'en'
  i18n.changeLanguage(next)
  localStorage.setItem('lang', next)
  document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
  document.documentElement.lang = next
}

/**
 * Shared public-page frame for Login and Signup: brand glow, centered card
 * slot, brandmark, language toggle, and legal footer. Each screen supplies
 * only its card body.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()

  return (
    <div className="min-h-screen bg-bg flex items-center justify-center px-4 py-12">
      <div aria-hidden className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-1/3 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-trace-blue/5 blur-3xl" />
      </div>

      <button
        type="button"
        onClick={() => toggleLang(i18n)}
        className="fixed top-5 end-5 z-20 inline-flex items-center gap-1.5 text-caption text-muted hover:text-primary hover:bg-white/5 transition-colors px-2.5 py-1.5 rounded-lg"
      >
        <Globe size={14} strokeWidth={1.75} />
        {i18n.language === 'en' ? 'العربية' : 'English'}
      </button>

      <div className="w-full max-w-sm relative z-10">
        <div className="text-center mb-10">
          <Logo variant="mark" size={28} className="justify-center mb-4" />
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
