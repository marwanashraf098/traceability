import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { FileText, Printer, Tag } from 'lucide-react'
import {
  getPackPrintPlan, getPrintBatchOptions, openPackLabelsPdf, PackLabelScope, PackLabelSummary, PackPrintPlan,
  PrintJobRunningError, PrintPaper, PrintSort, printPackLabels, ServerMessageError, startPrintJob, TransferCommandError,
} from '../../api'
import { Alert, Button, Checkbox, Modal, Radio, SegmentedControl, Spinner, cn } from '../../components/ui'
import { PrintJobPanel } from './PrintJobPanel'

/**
 * Flexible labeling UI pass (2026-10-25) — ONE "Print" in Pick & Pack (queue mode and waybill mode, switch on only;
 * switch off keeps "Print waybills" and its dialog exactly). "Print for packing": which orders (New only /
 * Everything ready to pack / Same orders as the last print), the order, and what to print — waybills and piece
 * labels, both from the SAME scope + sort, so the waybill stack and the label strip match.
 *
 * Waybills keep today's print job path untouched (startPrintJob: Bosta, 49 per request, 490 cap, the same PDF; "Same
 * orders as the last print" = a reprint job of that batch). Labels keep the slice 4 path (printPackLabels; parts of
 * at most 490 orders / 2,000 labels). The result shows them separately — never one merged PDF — and a waybill
 * failure never stops the labels.
 */
export default function PrintForPackingDialog({ onClose }: { onClose: (printed: boolean) => void }) {
  const { t, i18n } = useTranslation()
  const [scope, setScope] = useState<PackLabelScope>('new')
  const [sort, setSort] = useState<PrintSort>('oldest')
  const [paper, setPaper] = useState<PrintPaper>('A4')
  const [wantWaybills, setWantWaybills] = useState(true)
  const [wantLabels, setWantLabels] = useState(true)
  const [plan, setPlan] = useState<PackPrintPlan | null>(null)
  const [loading, setLoading] = useState(true)
  const [planError, setPlanError] = useState<string | null>(null)
  const [printing, setPrinting] = useState(false)
  const [done, setDone] = useState<null | {
    waybillJob: string | null; waybillError: string | null; waybillAlreadyRunning: boolean
    labels: PackLabelSummary | null; labelError: string | null
  }>(null)
  const [opening, setOpening] = useState<number | null>(null)
  const [openError, setOpenError] = useState<string | null>(null)

  const readable = (e: unknown) =>
    e instanceof ServerMessageError ? e.messageFor(i18n.language)
      : e instanceof TransferCommandError ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
      : t('fulfill.printBatch.error')

  useEffect(() => {
    let cancelled = false
    getPrintBatchOptions()
      .then(o => { if (!cancelled && o?.defaultPaper) setPaper(o.defaultPaper) })
      .catch(() => { /* keep A4 */ })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true); setPlanError(null)
    getPackPrintPlan({ scope, sort })
      .then(p => { if (!cancelled) setPlan(p) })
      .catch(e => { if (!cancelled) setPlanError(readable(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope, sort])

  const waybillCount = plan?.waybills ?? 0
  const labelCount = plan?.labels.labels ?? 0
  const waybillsOn = wantWaybills && waybillCount > 0
  const labelsOn = wantLabels && labelCount > 0
  const canPrint = !loading && !!plan && (waybillsOn || labelsOn)

  async function print() {
    if (!canPrint || printing || !plan) return
    setPrinting(true)
    const result = { waybillJob: null as string | null, waybillError: null as string | null, waybillAlreadyRunning: false,
      labels: null as PackLabelSummary | null, labelError: null as string | null }
    const jobs: Promise<void>[] = []
    if (waybillsOn) {
      jobs.push((async () => {
        try {
          result.waybillJob = scope === 'last' && plan.lastBatchId
            ? await startPrintJob({ kind: 'reprint', batchId: plan.lastBatchId })
            : await startPrintJob({ kind: 'print', scope: scope === 'last' ? 'all' : scope, paper, sort })
        } catch (e) {
          if (e instanceof PrintJobRunningError) { result.waybillJob = e.jobId; result.waybillAlreadyRunning = true }
          else result.waybillError = readable(e)
        }
      })())
    }
    if (labelsOn) {
      jobs.push((async () => {
        try { result.labels = await printPackLabels({ scope, sort }) }
        catch (e) { result.labelError = readable(e) }
      })())
    }
    await Promise.all(jobs)      // independent: a waybill failure never stops the labels
    setDone(result)
    setPrinting(false)
  }

  async function openPart(i: number, labels: PackLabelSummary) {
    setOpening(i); setOpenError(null)
    try { await openPackLabelsPdf(labels.parts[i].orderIds) }
    catch (e) { setOpenError(readable(e)) }
    finally { setOpening(null) }
  }

  if (done) {
    return (
      <Modal title={t('fulfill.print.readyTitle')} onClose={() => onClose(true)}>
        <div className="space-y-4" data-testid="print-ready">
          {(done.waybillJob || done.waybillError) && (
            <section className="space-y-2" data-testid="print-ready-waybills">
              <p className="text-small font-semibold text-primary flex items-center gap-1.5">
                <FileText size={14} strokeWidth={2} />
                {scope === 'last' ? t('fulfill.print.waybillsHeading') : t('fulfill.print.waybillsHeadingPaper', { paper })}
              </p>
              {done.waybillError
                ? <Alert tone="critical" title={done.waybillError} />
                : <PrintJobPanel jobId={done.waybillJob!} alreadyRunning={done.waybillAlreadyRunning} actions={() => null} />}
            </section>
          )}
          {(done.labels || done.labelError) && (
            <section className="space-y-2" data-testid="print-ready-labels">
              <p className="text-small font-semibold text-primary flex items-center gap-1.5">
                <Tag size={14} strokeWidth={2} />
                {t('fulfill.print.labelsHeading')}
              </p>
              {done.labelError ? <Alert tone="critical" title={done.labelError} /> : done.labels && (
                done.labels.parts.length === 0
                  ? <p className="text-small text-muted">{t('fulfill.print.noLabelsNow')}</p>
                  : (
                    <div className="flex flex-col gap-2">
                      {done.labels.parts.map((p, i) => (
                        <Button key={p.part} variant="secondary" iconStart={Printer} loading={opening === i}
                          onClick={() => openPart(i, done.labels!)}>
                          {done.labels!.parts.length > 1
                            ? t('fulfill.print.openLabelsPart', { part: p.part, of: p.of, count: p.labels })
                            : t('fulfill.print.openLabels', { count: p.labels })}
                        </Button>
                      ))}
                    </div>
                  )
              )}
              {openError && <Alert tone="critical" title={openError} />}
            </section>
          )}
          <Button className="w-full" onClick={() => onClose(true)}>{t('fulfill.printBatch.done')}</Button>
        </div>
      </Modal>
    )
  }

  const scopeOption = (value: PackLabelScope, label: string, hint: string) => (
    <label className={cn('flex items-center gap-3 rounded-xl border p-3 cursor-pointer transition-colors',
      scope === value ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated')}>
      <Radio value={value} checked={scope === value} onChange={v => setScope(v as PackLabelScope)} />
      <span className="flex-1 min-w-0">
        <span className="block text-body font-semibold text-primary">{label}</span>
        <span className="block text-small text-muted">{hint}</span>
      </span>
    </label>
  )

  const what = (kind: 'waybills' | 'labels') => {
    const count = kind === 'waybills' ? waybillCount : labelCount
    const checked = kind === 'waybills' ? waybillsOn : labelsOn
    const disabled = loading || !plan || count === 0
    const label = kind === 'waybills'
      ? t('fulfill.print.waybills', { count: waybillCount })
      : t('fulfill.print.labels', { count: labelCount, orders: plan?.labels.orders ?? 0 })
    // No labels here, but "Everything ready to pack" (the whole queue) has some → say so.
    const reason = kind === 'waybills' ? t('fulfill.print.noWaybills')
      : scope !== 'all' && plan?.labels.queueNeedsLabels ? t('fulfill.print.noLabelsUseAll') : t('fulfill.print.noLabels')
    return (
      <div data-testid={`print-what-${kind}`} className={cn('rounded-xl border border-line p-3', disabled && 'opacity-80')}>
        <Checkbox checked={checked} disabled={disabled}
          onChange={v => (kind === 'waybills' ? setWantWaybills(v) : setWantLabels(v))}
          label={
            <span>
              <span className="block text-body text-primary">{loading ? t('fulfill.print.counting') : label}</span>
              {!loading && plan && count === 0 && (
                <span className="block text-small text-muted" data-testid={`print-reason-${kind}`}>{reason}</span>
              )}
            </span>
          } />
      </div>
    )
  }

  return (
    <Modal title={t('fulfill.print.title')} onClose={() => onClose(false)}>
      <div className="space-y-4" data-testid="print-for-packing">
        <div className="space-y-2">
          <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.which')}</p>
          {scopeOption('new', t('fulfill.printBatch.newOnly'), t('fulfill.print.newHint'))}
          {scopeOption('all', t('fulfill.printBatch.everything'), t('fulfill.print.allHint'))}
          {scopeOption('last', t('fulfill.print.last'), t('fulfill.print.lastHint'))}
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-3">
          {scope !== 'last' && (
            <div className="space-y-1.5">
              <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.order')}</p>
              <SegmentedControl value={sort} onChange={v => setSort(v as PrintSort)} options={[
                { value: 'oldest', label: t('fulfill.printBatch.oldest') },
                { value: 'newest', label: t('fulfill.printBatch.newest') },
              ]} />
            </div>
          )}
          {waybillsOn && scope !== 'last' && (
            <div className="space-y-1.5">
              <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.paper')}</p>
              <SegmentedControl value={paper} onChange={v => setPaper(v as PrintPaper)} options={[
                { value: 'A6', label: t('fulfill.printBatch.paperA6') },
                { value: 'A4', label: 'A4' },
              ]} />
            </div>
          )}
        </div>

        <div className="space-y-2">
          <p className="text-small font-semibold text-primary">{t('fulfill.print.what')}</p>
          {what('waybills')}
          {what('labels')}
        </div>

        {planError && <Alert tone="critical" title={planError} />}

        <div className="flex gap-2 pt-1">
          <Button variant="secondary" className="flex-1" onClick={() => onClose(false)} disabled={printing}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" iconStart={Printer} loading={printing} disabled={!canPrint} onClick={print}>
            {printing ? t('fulfill.print.printing') : t('fulfill.print.print')}
          </Button>
        </div>
        {loading && <div className="flex justify-center"><Spinner size={16} /></div>}
      </div>
    </Modal>
  )
}
