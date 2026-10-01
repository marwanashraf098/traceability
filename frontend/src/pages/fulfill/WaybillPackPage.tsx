import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ChevronRight, Printer, ScanLine } from 'lucide-react'
import Layout from '../../components/Layout'
import { Alert, Button, Skeleton, StatCard } from '../../components/ui'
import {
  getFulfillQueue, getPackSummary, getRoleFromToken, startPackSession, TransferCommandError,
  FulfillQueueRow, PackSessionView,
} from '../../api'
import PrintWaybillsDialog from './PrintWaybillsDialog'
import PackSessionScreen from './PackSessionScreen'

// Pick & Pack S3 — the Pick & Pack page in waybill-scan mode (design/pick-pack-waybill-mockup/
// Main.html, header + tiles only; batch lists and "printed but not packed" are S4/S5).
// Two internal views: the page itself (inside <Layout>) and the pack session (full-screen
// immersive, no <Layout> — the active scan loop, per the worker-screen rule).

export default function WaybillPackPage() {
  const [session, setSession] = useState<PackSessionView | null>(null)

  if (session) {
    return <PackSessionScreen initial={session} onEnded={() => setSession(null)} />
  }
  return <WaybillMain onSession={setSession} />
}

function WaybillMain({ onSession }: { onSession: (s: PackSessionView) => void }) {
  const { t, i18n } = useTranslation()
  const [queue, setQueue] = useState<FulfillQueueRow[] | null>(null)
  const [packedToday, setPackedToday] = useState<number | null>(null)
  const [openSessionId, setOpenSessionId] = useState<string | null>(null)
  const [showPrint, setShowPrint] = useState(false)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isOwner = getRoleFromToken() === 'owner'

  const load = useCallback(async () => {
    try {
      const [q, s] = await Promise.all([getFulfillQueue(), getPackSummary()])
      setQueue(q ?? [])
      setPackedToday(s?.packedToday ?? 0)
      setOpenSessionId(s?.openSessionId ?? null)
    } catch {
      setQueue([])
      setError(t('fulfill.waybill.session.error'))
    }
  }, [t])

  useEffect(() => { load() }, [load])

  async function start() {
    if (starting) return
    setStarting(true)
    setError(null)
    try {
      onSession(await startPackSession())
    } catch (e) {
      setError(e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('fulfill.waybill.session.error'))
    } finally {
      setStarting(false)
    }
  }

  const ready = (queue ?? []).filter(o => !o.is_self_pickup && o.status !== 'self_pickup_pending')
  const printed = ready.filter(o => o.awb_printed).length
  const selfPickup = (queue ?? []).filter(o => o.is_self_pickup || o.status === 'self_pickup_pending').length

  return (
    <Layout>
      <div data-testid="waybill-pack-page" className="space-y-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <h1 className="text-h1 text-primary">{t('fulfill.title')}</h1>
            <div className="flex flex-wrap items-center gap-2 mt-1.5">
              <span className="inline-flex items-center gap-1.5 text-caption font-semibold text-trace-blue bg-trace-blue/[0.10] border border-trace-blue/30 rounded-full px-2.5 py-0.5">
                <ScanLine size={12} strokeWidth={2} />
                {t('fulfill.waybill.modeChip')}
              </span>
              {isOwner && (
                <Link to="/settings?tab=pickpack" className="text-caption text-muted hover:text-primary underline-offset-2 hover:underline">
                  {t('fulfill.waybill.changeInSettings')}
                </Link>
              )}
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Button variant="secondary" iconStart={Printer} onClick={() => setShowPrint(true)} disabled={ready.length === 0}>
              {t('fulfill.printBatch.button')}
            </Button>
            <Button iconStart={ScanLine} loading={starting} onClick={start}>
              {openSessionId ? t('fulfill.waybill.resumeSession') : t('fulfill.waybill.startSession')}
            </Button>
          </div>
        </div>

        {error && <Alert tone="critical" title={error} />}

        {queue === null ? (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-28 rounded-2xl" />)}
          </div>
        ) : (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4" data-testid="waybill-tiles">
            <StatCard label={t('fulfill.waybill.tiles.ready')} value={ready.length} accent />
            <StatCard label={t('fulfill.waybill.tiles.printed')} value={printed} />
            <StatCard label={t('fulfill.waybill.tiles.notPrinted')} value={ready.length - printed}
              tone={ready.length - printed > 0 ? 'warning' : undefined} />
            <StatCard label={t('fulfill.waybill.tiles.packedToday')} value={packedToday ?? 0} tone="success" />
          </div>
        )}

        {selfPickup > 0 && (
          <Link
            to="/fulfill?view=self-pickup"
            className="card p-4 flex items-center justify-between gap-3 hover:bg-elevated transition-colors"
            data-testid="self-pickup-entry"
          >
            <span>
              <span className="block text-body font-semibold text-primary">
                {t('fulfill.waybill.selfPickup', { count: selfPickup })}
              </span>
              <span className="block text-small text-muted">{t('fulfill.waybill.selfPickupHint')}</span>
            </span>
            <ChevronRight size={16} strokeWidth={2} className="text-muted rtl:rotate-180" />
          </Link>
        )}
      </div>

      {showPrint && (
        <PrintWaybillsDialog
          newCount={ready.length - printed}
          allCount={ready.length}
          onClose={didPrint => { setShowPrint(false); if (didPrint) load() }}
        />
      )}
    </Layout>
  )
}
