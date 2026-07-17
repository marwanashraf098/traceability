import { NavLink, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useState, useRef, ReactNode } from 'react'
import {
  LayoutGrid, ClipboardList, Package, PackageOpen, ClipboardCheck,
  Truck, RotateCcw, AlertTriangle, PlugZap, MapPin, ShoppingBag,
  Users, Settings, LogOut, Globe, Search,
} from 'lucide-react'
import { getRoleFromToken, request } from '../api'
import { clearAccessToken } from '../auth'
import { Logo } from './Logo'

// ── Nav link ──────────────────────────────────────────────────────────────────

function SideNavLink({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => isActive ? 'nav-item-active' : 'nav-item'}
    >
      {icon}
      <span>{label}</span>
    </NavLink>
  )
}

// ── Layout ────────────────────────────────────────────────────────────────────

export default function Layout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation()
  const navigate    = useNavigate()
  const [searchQ, setSearchQ] = useState('')
  const searchRef   = useRef<HTMLInputElement>(null)
  const role        = getRoleFromToken()

  async function logout() {
    try { await request<void>('/auth/logout', { method: 'POST' }) } catch { /* ignore */ }
    clearAccessToken()
    navigate('/login')
  }

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    const q = searchQ.trim()
    if (!q) return
    navigate(`/lookup?q=${encodeURIComponent(q)}`)
    setSearchQ('')
    searchRef.current?.blur()
  }

  function toggleLang() {
    const next = i18n.language === 'en' ? 'ar' : 'en'
    i18n.changeLanguage(next)
    localStorage.setItem('lang', next)
    document.documentElement.dir  = next === 'ar' ? 'rtl' : 'ltr'
    document.documentElement.lang = next
  }

  const iconProps = { size: 18, strokeWidth: 1.75 } as const

  return (
    <div className="flex h-screen bg-bg overflow-hidden">

      {/* ── Sidebar ── */}
      <aside className="w-56 flex-shrink-0 bg-panel border-e border-line flex flex-col">

        {/* Wordmark */}
        <div className="flex items-center gap-2.5 px-5 py-5 border-b border-line">
          <Logo variant="icon" size={32} />
          <span className="text-primary font-light text-xl tracking-tight select-none">
            <span className="text-trace-blue">tr</span>aced
          </span>
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto px-3 py-3 space-y-0.5">
          <SideNavLink to="/overview"    icon={<LayoutGrid    {...iconProps} />} label={t('nav.overview')} />
          <SideNavLink to="/orders"      icon={<ClipboardList {...iconProps} />} label={t('nav.orders')} />
          <SideNavLink to="/catalog"     icon={<Package       {...iconProps} />} label={t('nav.catalog')} />
          <SideNavLink to="/receiving"   icon={<PackageOpen   {...iconProps} />} label={t('nav.receiving')} />
          <SideNavLink to="/fulfill"     icon={<ClipboardCheck {...iconProps} />} label={t('nav.fulfill')} />
          <SideNavLink to="/pickups"     icon={<Truck         {...iconProps} />} label={t('nav.pickups')} />
          <SideNavLink to="/returns"     icon={<RotateCcw     {...iconProps} />} label={t('nav.returns')} />
          <SideNavLink to="/exceptions"  icon={<AlertTriangle {...iconProps} />} label={t('nav.exceptions')} />
          <SideNavLink to="/connections" icon={<PlugZap       {...iconProps} />} label={t('nav.connections')} />
          {role !== 'worker' && (
            <>
              <SideNavLink to="/locations"         icon={<MapPin       {...iconProps} />} label={t('nav.locations')} />
              <SideNavLink to="/shopify-inventory"  icon={<ShoppingBag  {...iconProps} />} label={t('nav.shopifyInventory')} />
              <SideNavLink to="/onboarding"         icon={<ClipboardCheck {...iconProps} />} label={t('nav.onboarding')} />
              <SideNavLink to="/users"              icon={<Users        {...iconProps} />} label={t('nav.users')} />
              <SideNavLink to="/settings"           icon={<Settings     {...iconProps} />} label={t('nav.settings')} />
            </>
          )}
        </nav>

        {/* Bottom: lang toggle + logout */}
        <div className="px-3 py-3 border-t border-line space-y-1">
          <button onClick={toggleLang} className="nav-item w-full text-start">
            <Globe size={18} strokeWidth={1.75} />
            <span>{i18n.language === 'en' ? 'العربية' : 'English'}</span>
          </button>
          <button
            onClick={logout}
            className="nav-item w-full text-start text-danger hover:text-danger"
          >
            <LogOut size={18} strokeWidth={1.75} />
            <span>{t('nav.logout')}</span>
          </button>
        </div>
      </aside>

      {/* ── Main area ── */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">

        {/* Top bar */}
        <header className="h-14 border-b border-line bg-panel/50 backdrop-blur flex items-center px-6 gap-4 flex-shrink-0">
          <form onSubmit={handleSearch} className="flex-1 max-w-sm">
            <div className="relative">
              <span className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none">
                <Search size={15} strokeWidth={2} />
              </span>
              <input
                ref={searchRef}
                type="text"
                value={searchQ}
                onChange={e => setSearchQ(e.target.value)}
                placeholder={t('nav.lookup')}
                className="input ps-9 text-small py-1.5 w-full"
              />
            </div>
          </form>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-6">
          {children}
        </main>
      </div>
    </div>
  )
}
