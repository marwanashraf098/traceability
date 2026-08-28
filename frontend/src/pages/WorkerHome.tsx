import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { PackageCheck, Undo2, Truck, Search, ChevronRight, type LucideIcon } from 'lucide-react'
import { useMe } from '../components/ui'

interface Tile {
  to: string
  icon: LucideIcon
  iconClass: string
  badgeClass: string
  titleKey: string
  descKey: string
}

const TILES: Tile[] = [
  {
    to: '/fulfill', icon: PackageCheck,
    iconClass: 'text-trace-blue', badgeClass: 'bg-trace-blue/15',
    titleKey: 'workerHome.tiles.fulfill.title', descKey: 'workerHome.tiles.fulfill.desc',
  },
  {
    to: '/returns', icon: Undo2,
    iconClass: 'text-warning', badgeClass: 'bg-warning/15',
    titleKey: 'workerHome.tiles.returns.title', descKey: 'workerHome.tiles.returns.desc',
  },
  {
    to: '/pickups', icon: Truck,
    iconClass: 'text-info', badgeClass: 'bg-info/15',
    titleKey: 'workerHome.tiles.pickups.title', descKey: 'workerHome.tiles.pickups.desc',
  },
  {
    to: '/lookup', icon: Search,
    iconClass: 'text-success', badgeClass: 'bg-success/15',
    titleKey: 'workerHome.tiles.lookup.title', descKey: 'workerHome.tiles.lookup.desc',
  },
]

export default function WorkerHome() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const me = useMe()
  const firstName = me?.name?.trim().split(/\s+/)[0] ?? ''

  return (
    <div className="space-y-6" data-testid="worker-home">
      <div>
        <h1 className="text-h1 text-primary">
          {t('workerHome.greeting', { name: firstName })}
        </h1>
        <p className="text-body text-muted mt-1">{t('workerHome.subtitle')}</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {TILES.map(tile => (
          <button
            key={tile.to}
            type="button"
            onClick={() => navigate(tile.to)}
            className="group flex items-center gap-4 rounded-xl border border-line bg-surface p-5 text-start transition-colors hover:border-brand/50 hover:bg-white/5"
            data-testid={`worker-home-tile-${tile.to.slice(1)}`}
          >
            <span className={`w-12 h-12 rounded-full flex items-center justify-center shrink-0 ${tile.badgeClass}`}>
              <tile.icon size={22} strokeWidth={1.75} className={tile.iconClass} />
            </span>
            <span className="flex-1 min-w-0">
              <span className="block text-body-lg font-semibold text-primary">{t(tile.titleKey)}</span>
              <span className="block text-small text-muted mt-0.5">{t(tile.descKey)}</span>
            </span>
            <ChevronRight
              size={18}
              strokeWidth={2}
              className="text-muted shrink-0 transition-transform rtl:rotate-180 group-hover:translate-x-0.5 rtl:group-hover:-translate-x-0.5"
            />
          </button>
        ))}
      </div>
    </div>
  )
}
