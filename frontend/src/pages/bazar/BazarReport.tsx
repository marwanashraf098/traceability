import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Download } from 'lucide-react'
import { BazarError, downloadBazarCustomers, getBazarReport, type BazarReport } from '../../bazar/api'
import { formatMoney, shortDate, toNumber, transferCode } from '../../bazar/format'
import { Badge, Button, Spinner, cn } from '../../components/ui'
import { STATUS_TONE } from './BazarEvents'

// Bazar POS B5 — the event report (mockup page 1, step 10): /bazar/:id/report, desktop, in-shell. Read-only, owner /
// manager. Charts are plain bars. Built from GET /report only (bazar tables + the linked transfer).

const pctText = (v: string | number | null | undefined) => (v == null ? '—' : `${Math.round(toNumber(v) * 100)}%`)

export default function BazarReportPage() {
  const { id } = useParams<{ id: string }>()
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [r, setR] = useState<BazarReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    getBazarReport(id).then(setR).catch(e => {
      if (e instanceof BazarError && e.code === 'BAZAR_NOT_ENABLED') { navigate('/overview', { replace: true }); return }
      setError(e instanceof BazarError ? e.message_(i18n.language) : t('bazar.report.loadFailed'))
    })
  }, [id, navigate, t, i18n.language])

  if (error && !r) return <p role="alert" className="text-body text-critical">{error}</p>
  if (!r || !id) return <div className="py-16 flex justify-center"><Spinner size={28} /></div>
  return <ReportView id={id} r={r} />
}

function ReportView({ id, r }: { id: string; r: BazarReport }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const [exporting, setExporting] = useState(false)
  const [exportError, setExportError] = useState(false)
  const nr = r.money_not_received
  const maxDay = Math.max(1, ...r.sales_by_day.map(d => Math.abs(toNumber(d.revenue))))
  const maxHour = Math.max(1, ...r.busiest_hours.map(h => h.sales))
  const maxProduct = Math.max(1, ...r.top_products.map(p => p.sold))
  const maxSize = Math.max(1, ...r.top_sizes.map(p => p.sold))
  const moneyIn = Math.max(0, toNumber(r.money_in))

  async function exportCsv() {
    setExporting(true)
    setExportError(false)
    try { await downloadBazarCustomers(id, `bazar-customers-${r.event.starts_on}.csv`) }
    catch { setExportError(true) }
    finally { setExporting(false) }
  }

  return (
    <div className="flex flex-col gap-5 max-w-[1080px]" data-testid="bazar-report">
      <Link to="/bazar" className="text-small text-muted hover:text-primary self-start">{t('bazar.report.back')}</Link>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-1.5">
          <h1 className="text-h2 text-primary">{r.event.name}</h1>
          <span className="text-body text-muted">
            {t('bazar.report.title')} · {shortDate(r.event.starts_on, lang)} – {shortDate(r.event.ends_on, lang)} · {r.event.location_name}
          </span>
        </div>
        <Badge tone={STATUS_TONE[r.event.status]} label={t(`bazar.status.${r.event.status}`)} />
      </div>

      <ReportKpis r={r} />

      <div className="grid gap-5 md:grid-cols-2">
        <Card title={t('bazar.report.byDay')} testId="report-by-day">
          {r.sales_by_day.length === 0 ? <Empty /> : r.sales_by_day.map(d => (
            <BarRow key={d.business_day} label={`${t('bazar.report.day', { n: d.day_number })} · ${shortDate(d.business_day, lang)}`}
              value={formatMoney(d.revenue)} ratio={Math.abs(toNumber(d.revenue)) / maxDay} />
          ))}
        </Card>
        <Card title={t('bazar.report.hours')} sub={t('bazar.report.hoursSub')} testId="report-hours">
          {r.busiest_hours.length === 0 ? <Empty /> : (
            <div className="flex items-end gap-1 h-32" dir="ltr">
              {r.busiest_hours.map(h => (
                <div key={h.hour} className="flex-1 flex flex-col items-center gap-1 min-w-0">
                  <span className="text-[11px] text-muted tabular-nums">{h.sales}</span>
                  <div className="w-full rounded-t bg-trace-blue" style={{ height: `${(h.sales / maxHour) * 88}px` }} />
                  <span className="text-[11px] text-muted tabular-nums">{String(h.hour).padStart(2, '0')}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title={t('bazar.report.topProducts')} testId="report-top-products">
          {r.top_products.length === 0 ? <Empty /> : r.top_products.map(p => (
            <BarRow key={p.product_title} label={p.product_title}
              value={`${p.sold} / ${p.sent} · ${pctText(p.sell_through)}`} ratio={p.sold / maxProduct} />
          ))}
        </Card>
        <Card title={t('bazar.report.topSizes')} testId="report-top-sizes">
          {r.top_sizes.length === 0 ? <Empty /> : r.top_sizes.map(p => (
            <BarRow key={p.product_title + p.variant_title} label={`${p.product_title} · ${p.variant_title}`}
              value={`${p.sold} / ${p.sent} · ${pctText(p.sell_through)}`} ratio={p.sold / maxSize} />
          ))}
        </Card>
        <Card title={t('bazar.report.paymentSplit')} testId="report-payment-split">
          <div className="flex h-3 rounded-full overflow-hidden bg-charcoal" aria-hidden>
            {r.payment_split.map((m, i) => (
              <div key={m.method} className={['bg-trace-blue', 'bg-[#16A34A]', 'bg-warning'][i % 3]}
                   style={{ width: moneyIn ? `${(Math.max(0, toNumber(m.amount)) / moneyIn) * 100}%` : '0%' }} />
            ))}
          </div>
          {r.payment_split.map((m, i) => (
            <div key={m.method} className="flex justify-between gap-2 text-body">
              <span className="flex items-center gap-2 text-primary">
                <span className={cn('w-2.5 h-2.5 rounded-sm', ['bg-trace-blue', 'bg-[#16A34A]', 'bg-warning'][i % 3])} aria-hidden />
                {t(`bazar.method.${m.method}`)}
              </span>
              <span className="tabular-nums text-primary">{formatMoney(m.amount)} <span className="text-muted">· {pctText(m.share)}</span></span>
            </div>
          ))}
        </Card>
        <Card title={t('bazar.report.notReceived')} sub={t('bazar.report.notReceivedSub')} testId="report-not-received">
          <Line label={t('bazar.report.cashShort')} value={formatMoney(nr.cash_short)} />
          <Line label={t('bazar.report.cardShort')} value={formatMoney(nr.card_short)} />
          <Line label={t('bazar.report.instapay')} value={formatMoney(nr.instapay_not_received)} />
          {toNumber(nr.cash_over) > 0 && <Line label={t('bazar.report.cashOver')} value={formatMoney(nr.cash_over)} />}
          {toNumber(nr.card_over) > 0 && <Line label={t('bazar.report.cardOver')} value={formatMoney(nr.card_over)} />}
          <div className={cn('flex justify-between gap-2 pt-2 border-t border-line font-bold',
            toNumber(nr.total) > 0 ? 'text-critical-text' : 'text-primary')}>
            <span>{t('bazar.report.total')}</span><span className="tabular-nums">{formatMoney(nr.total)}</span>
          </div>
          {nr.days_not_closed > 0 && (
            <p className="text-small text-warning-text">{t('bazar.report.daysNotClosed', { count: nr.days_not_closed })}</p>
          )}
        </Card>
        <Card title={t('bazar.report.perSeller')} testId="report-per-seller">
          {r.per_seller.length === 0 ? <Empty /> : r.per_seller.map(s => (
            <Line key={s.seller_name} label={s.seller_name}
              value={`${t('bazar.phone.pieces', { count: s.pieces })} · ${formatMoney(s.total)}`} />
          ))}
          <p className="text-small text-muted pt-1">
            {t('bazar.report.returns', { count: r.returns })} · {t('bazar.report.exchanges', { count: r.exchanges })}
          </p>
        </Card>
        <Card title={t('bazar.report.customers')} testId="report-customers">
          <p className="text-h4 text-primary">{t('bazar.report.customersSaved', { count: r.customers_saved })}</p>
          <p className="text-small text-muted">{t('bazar.report.exportNote')}</p>
          <Button variant="secondary" iconStart={Download} onClick={exportCsv} loading={exporting}
                  disabled={r.customers_saved === 0} className="self-start">{t('bazar.report.exportCsv')}</Button>
          {exportError && <p role="alert" className="text-small text-critical">{t('bazar.report.exportFailed')}</p>}
        </Card>
      </div>

      <Card title={t('bazar.report.transfer')} testId="report-transfer">
        <div className="flex items-center justify-between gap-2">
          <span className="font-mono text-primary" dir="ltr">{transferCode(r.transfer.transfer_id)}</span>
          <Link to={`/transfers/${r.transfer.transfer_id}`} className="text-small font-semibold text-trace-blue">{t('bazar.live.openTransfer')}</Link>
        </div>
        <p className="text-body text-primary tabular-nums" dir="auto">
          {t('bazar.end.equation', { sent: r.transfer.sent, sold: r.transfer.sold, back: r.transfer.back,
                                     damaged: r.transfer.damaged, lost: r.transfer.lost })}
        </p>
        {r.transfer.outstanding > 0 && (
          <p className="text-small text-warning-text">{t('bazar.end.stillOut', { count: r.transfer.outstanding })}</p>
        )}
      </Card>
    </div>
  )
}

/** Revenue, pieces sold, sell-through, average sale — the phone's summary shows only these. */
export function ReportKpis({ r }: { r: BazarReport }) {
  const { t } = useTranslation()
  const items = [
    { k: 'revenue', label: t('bazar.report.revenue'), value: formatMoney(r.revenue), sub: t('bazar.report.revenueSub') },
    { k: 'sold', label: t('bazar.report.piecesSold'), value: String(r.pieces_sold), sub: t('bazar.report.ofSent', { count: r.pieces_sent }) },
    { k: 'through', label: t('bazar.report.sellThrough'), value: pctText(r.sell_through), sub: t('bazar.report.ofSent', { count: r.pieces_sent }) },
    { k: 'avg', label: t('bazar.report.averageSale'), value: r.average_sale == null ? '—' : formatMoney(r.average_sale), sub: '' },
  ]
  return (
    <section className="grid grid-cols-2 md:grid-cols-4 gap-3" data-testid="report-kpis">
      {items.map(i => (
        <div key={i.k} className="card p-4 flex flex-col gap-1" data-testid={`kpi-${i.k}`}>
          <span className="text-small text-muted">{i.label}</span>
          <span className="text-h3 font-semibold text-primary tabular-nums">{i.value}</span>
          {i.sub && <span className="text-small text-muted">{i.sub}</span>}
        </div>
      ))}
    </section>
  )
}

function Card({ title, sub, children, testId }: { title: string; sub?: string; children: React.ReactNode; testId?: string }) {
  return (
    <section className="card p-5 flex flex-col gap-2.5 min-w-0" data-testid={testId}>
      <div className="flex flex-col">
        <h2 className="text-body-lg font-semibold text-primary">{title}</h2>
        {sub && <span className="text-small text-muted">{sub}</span>}
      </div>
      {children}
    </section>
  )
}

function BarRow({ label, value, ratio }: { label: string; value: string; ratio: number }) {
  return (
    <div className="flex flex-col gap-1">
      <div className="flex justify-between gap-2 text-body">
        <span className="text-primary truncate">{label}</span>
        <span className="tabular-nums text-primary shrink-0" dir="ltr">{value}</span>
      </div>
      <div className="h-2 rounded-full bg-charcoal overflow-hidden" aria-hidden>
        <div className="h-full rounded-full bg-trace-blue" style={{ width: `${Math.max(2, Math.min(1, ratio) * 100)}%` }} />
      </div>
    </div>
  )
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-2 text-body">
      <span className="text-primary">{label}</span>
      <span className="tabular-nums text-primary" dir="ltr">{value}</span>
    </div>
  )
}

function Empty() {
  const { t } = useTranslation()
  return <p className="text-body text-muted">{t('bazar.report.noSales')}</p>
}
