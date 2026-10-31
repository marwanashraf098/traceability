import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowLeft, ArrowRight, Printer, RefreshCw } from 'lucide-react'
import { Alert, Button, Spinner } from '../../components/ui'
import {
  getPutAwayList, startPutAwayList, PutAwayList, PutAwayListSource, ServerMessageError,
} from '../../api'
import PutAwayListTable from './PutAwayListTable'
import { PUT_AWAY_LIST_SOURCES, putAwayListBack } from './putAwayListPaths'

// Warehouse places S4 — a put-away list: what a receipt / Tag stock session / return session / transfer brought in,
// and where each piece should go (screen and print are the same table). A printable sheet like /fulfill/gather:
// NOT Layout-wrapped, its own Back. "Start put away" opens S2's scan loop on a session scoped to this list (Start
// resumes the caller's open one); the scan loop ticks the list off.

export default function PutAwayListPage() {
  const params = useParams<{ source: string; sourceId: string }>()
  const source = PUT_AWAY_LIST_SOURCES.includes(params.source as PutAwayListSource)
    ? (params.source as PutAwayListSource) : null
  const sourceId = params.sourceId ?? ''
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [list, setList] = useState<PutAwayList | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [starting, setStarting] = useState(false)
  const [printSize, setPrintSize] = useState<'A6' | 'A4'>('A6')
  const isRtl = i18n.dir() === 'rtl'
  const BackIcon = isRtl ? ArrowRight : ArrowLeft

  const readable = (e: unknown) => e instanceof ServerMessageError ? e.messageFor(i18n.language) : t('putAway.error')

  function load() {
    if (!source) { setLoading(false); setError(t('putAway.error')); return }
    setLoading(true)
    getPutAwayList(source, sourceId)
      .then(l => { setList(l); setError(null) })
      .catch(e => setError(readable(e)))
      .finally(() => setLoading(false))
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [source, sourceId])

  async function start() {
    if (!source) return
    setStarting(true)
    setError(null)
    try {
      const s = await startPutAwayList(source, sourceId)
      navigate(`/put-away/${s.id}/scan?list=1`)
    } catch (e: unknown) {
      setError(readable(e))
      setStarting(false)
    }
  }

  function print(size: 'A6' | 'A4') {
    setPrintSize(size)
    // the @page rule below follows printSize — print after it has rendered
    window.setTimeout(() => window.print(), 0)
  }

  const back = () => navigate(source ? putAwayListBack(source, sourceId) : '/put-away')
  const waiting = list ? list.total - list.placed : 0
  const subtitle = !list ? '' : list.source === 'receiving'
    ? t('putAway.list.from.receiving', { ref: list.sourceRef ?? t('receiving.untitled') })
    : list.source === 'transfer'
      ? t('putAway.list.from.transfer', { ref: list.sourceRef ?? '' })
      : t(`putAway.list.from.${list.source}`)

  return (
    <div className="p-6 max-w-4xl mx-auto" data-testid="put-away-list">
      {/* Print-only rules: the gather list's sheet (light, chrome hidden), on A6 or A4 as chosen. */}
      <style>{`
        @media print {
          @page { size: ${printSize}; margin: ${printSize === 'A6' ? '6mm' : '12mm'}; }
          .put-away-no-print { display: none !important; }
          html, body { background: #ffffff !important; color: #111111 !important; }
          .text-primary { color: #111111 !important; }
          .text-muted   { color: #444444 !important; }
          .text-success { color: #111111 !important; }
          .tbl-header { color: #444444 !important; }
          .tbl-cell   { color: #111111 !important; }
          .card { background: #ffffff !important; border-color: #cccccc !important; box-shadow: none !important; }
          .bg-elevated { background: #f0f0f0 !important; }
          table, th, td, tr { border-color: #cccccc !important; }
          table { width: 100% !important; font-size: ${printSize === 'A6' ? '10px' : '12px'}; }
          th, td { padding: 3px 5px !important; }
        }
      `}</style>

      <div className="flex items-center justify-between flex-wrap gap-2 mb-2 put-away-no-print">
        <div className="flex items-center gap-3">
          <button onClick={back}
            className="flex items-center gap-1.5 text-primary hover:bg-elevated transition-colors -ms-1 ps-2 pe-3 py-2 rounded-lg">
            <BackIcon size={18} strokeWidth={2} />
            <span className="text-small font-semibold">{t('putAway.list.back')}</span>
          </button>
          <h1 className="text-h1 text-primary">{t('putAway.list.title')}</h1>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" iconStart={RefreshCw} onClick={load}>{t('putAway.list.refresh')}</Button>
          <Button variant="secondary" size="sm" iconStart={Printer} onClick={() => print('A6')} disabled={!list || list.total === 0}>
            {t('putAway.list.printA6')}
          </Button>
          <Button variant="secondary" size="sm" iconStart={Printer} onClick={() => print('A4')} disabled={!list || list.total === 0}>
            {t('putAway.list.printA4')}
          </Button>
        </div>
      </div>

      <h1 className="hidden print:block text-h1 text-primary mb-1">{t('putAway.list.title')}</h1>

      {loading && !list ? (
        <div className="py-16 flex justify-center"><Spinner /></div>
      ) : !list ? (
        <Alert tone="critical" title={error ?? t('putAway.error')} />
      ) : (
        <>
          <p className="text-small text-muted mb-1" data-testid="put-away-list-subtitle">
            <bdi>{subtitle}</bdi>{' · '}<bdi>{list.locationName}</bdi>
          </p>
          <p className="text-body text-primary mb-5" data-testid="put-away-list-progress">
            {t('putAway.list.progress', { placed: list.placed, count: list.total })}
          </p>

          {error && <div className="mb-4 put-away-no-print"><Alert tone="critical" title={error} /></div>}

          {list.total === 0 ? (
            <div className="card p-8 text-center space-y-1" data-testid="put-away-list-empty">
              <p className="text-h3 text-primary">{t('putAway.list.emptyTitle')}</p>
              <p className="text-small text-muted">{t('putAway.list.emptyBody')}</p>
            </div>
          ) : (
            <>
              {!list.hasPlaces && (
                <div className="mb-4 put-away-no-print">
                  <Alert tone="warning" title={t('putAway.noPlacesTitle')}>{t('putAway.noPlacesBody')}</Alert>
                </div>
              )}
              <div className="mb-4 put-away-no-print flex flex-wrap items-center gap-3">
                {waiting > 0 ? (
                  <Button size="lg" loading={starting} disabled={!list.hasPlaces} onClick={start} data-testid="put-away-list-start">
                    {list.session && list.session.status === 'open'
                      ? t('putAway.resume', { count: list.session.placedInSession })
                      : t('putAway.start')}
                  </Button>
                ) : (
                  <p className="text-body text-success font-semibold" data-testid="put-away-list-all-placed">
                    {t('putAway.list.allPlaced')}
                  </p>
                )}
                <p className="text-small text-muted">{t('putAway.suggestionNote')}</p>
              </div>
              <PutAwayListTable pieces={list.pieces} />
            </>
          )}
        </>
      )}
    </div>
  )
}
