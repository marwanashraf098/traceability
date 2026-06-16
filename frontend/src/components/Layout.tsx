import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

export default function Layout({ children }: { children: React.ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()

  function logout() {
    localStorage.removeItem('token')
    navigate('/login')
  }

  function toggleLang() {
    const next = i18n.language === 'en' ? 'ar' : 'en'
    i18n.changeLanguage(next)
    localStorage.setItem('lang', next)
    document.documentElement.dir = next === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = next
  }

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `px-3 py-2 rounded text-sm font-medium transition-colors ${
      isActive
        ? 'bg-indigo-700 text-white'
        : 'text-indigo-100 hover:bg-indigo-600 hover:text-white'
    }`

  return (
    <div className="min-h-screen bg-gray-50">
      <nav className="bg-indigo-800 shadow">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-14 flex items-center justify-between">
          <div className="flex items-center gap-6">
            <span className="text-white font-semibold text-lg tracking-tight">Traceability</span>
            <NavLink to="/orders"    className={linkClass}>{t('nav.orders')}</NavLink>
            <NavLink to="/catalog"   className={linkClass}>{t('nav.catalog')}</NavLink>
            <NavLink to="/receiving" className={linkClass}>{t('nav.receiving')}</NavLink>
            <NavLink to="/fulfill"   className={linkClass}>{t('nav.fulfill')}</NavLink>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={toggleLang}
              className="text-xs text-indigo-200 hover:text-white border border-indigo-500 rounded px-2 py-1"
            >
              {i18n.language === 'en' ? 'عربي' : 'EN'}
            </button>
            <button
              onClick={logout}
              className="text-sm text-indigo-200 hover:text-white"
            >
              {t('nav.logout')}
            </button>
          </div>
        </div>
      </nav>
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        {children}
      </main>
    </div>
  )
}
