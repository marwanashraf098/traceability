import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Printer } from 'lucide-react'
import {
  getLatestPrintJob, getPrintBatchOptions, isPrintJobActive, MAX_WAYBILLS_PER_PRINT, PrintJob,
  PrintJobRunningError, PrintPaper, PrintScope, PrintSort, startPrintJob, TransferCommandError,
} from '../../api'
import { Alert, Button, Checkbox, Modal, Radio, SegmentedControl, cn } from '../../components/ui'
import { openJobPdf, PrintJobPanel } from './PrintJobPanel'

export { isRetryReason, printSkipReasonText, printSummaryText } from './PrintJobPanel'

// Pick & Pack S2 — "Print waybills" (design/pick-pack-waybill-mockup/PrintDialog.html for flow
// and copy). Counts come from the queue already on screen, so opening the page makes no extra
// request; the paper default and the latest print job are fetched only when this dialog opens.
// Merged-PDF slice C: a print is a background job of up to 490 waybills (requests of 49 to Bosta, one
// merged PDF); this dialog starts it, follows it, and finds it again after being closed.

export default function PrintWaybillsDialog({
  newCount,
  allCount,
  onClose,
}: {
  newCount: number
  allCount: number
  /** printed = a batch was recorded, so the caller should refresh its counts. Refreshing
   *  only on close keeps the result view on screen (a queue reload unmounts this dialog). */
  onClose: (printed: boolean) => void
}) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [scope, setScope] = useState<PrintScope>(newCount > 0 ? 'new' : 'all')
  const [paper, setPaper] = useState<PrintPaper>('A4')
  const [sort, setSort] = useState<PrintSort>('oldest')
  const [pickList, setPickList] = useState(false)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [jobId, setJobId] = useState<string | null>(null)
  const [alreadyRunning, setAlreadyRunning] = useState(false)
  const [lastJob, setLastJob] = useState<PrintJob | null>(null)
  const [finished, setFinished] = useState<PrintJob | null>(null)

  useEffect(() => {
    let cancelled = false
    getPrintBatchOptions()
      .then(o => { if (!cancelled && o?.defaultPaper) setPaper(o.defaultPaper) })
      .catch(() => { /* keep A4 */ })
    // A print left running (or finished) while the dialog was closed is found again here.
    getLatestPrintJob()
      .then(j => {
        if (cancelled || !j) return
        if (isPrintJobActive(j)) setJobId(j.jobId)
        else setLastJob(j)
      })
      .catch(() => { /* nothing to resume */ })
    return () => { cancelled = true }
  }, [])

  // The options show the real totals; the button says how many this print will actually send.
  const count = Math.min(scope === 'new' ? newCount : allCount, MAX_WAYBILLS_PER_PRINT)

  async function print() {
    if (starting || count === 0) return
    setStarting(true)
    setError(null)
    try {
      setJobId(await startPrintJob({ kind: 'print', scope, paper, sort }))
    } catch (e) {
      if (e instanceof PrintJobRunningError) {
        setAlreadyRunning(true)
        setJobId(e.jobId)
      } else {
        setError(e instanceof TransferCommandError
          ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
          : t('fulfill.printBatch.error'))
      }
    } finally {
      setStarting(false)
    }
  }

  const close = (job: PrintJob | null) => onClose(!!(job?.batchId ?? finished?.batchId))

  if (jobId) {
    return (
      <Modal title={t('fulfill.printBatch.title')} onClose={() => close(finished)}>
        <PrintJobPanel
          key={jobId}
          jobId={jobId}
          alreadyRunning={alreadyRunning}
          onJob={j => { if (!isPrintJobActive(j)) setFinished(j) }}
          actions={job => {
            const ended = job && !isPrintJobActive(job)
            return (
              <>
                {ended && pickList && job.batchId && (
                  <Button
                    variant="secondary"
                    className="flex-1 min-w-[10rem]"
                    onClick={() => {
                      // Same tab: a new tab on a station tablet has no signed-in worker and shows the PIN gate.
                      onClose(true)
                      navigate(`/fulfill/gather?batch=${job.batchId}`)
                    }}
                  >
                    {t('fulfill.printBatch.openPickList')}
                  </Button>
                )}
                <Button className="flex-1 min-w-[10rem]" variant={ended && job.pdfAvailable ? 'secondary' : 'primary'}
                        onClick={() => close(job)}>
                  {t('fulfill.printBatch.done')}
                </Button>
              </>
            )
          }}
        />
      </Modal>
    )
  }

  const scopeOption = (value: PrintScope, label: string, hint: string, n: number) => (
    <label
      className={cn(
        'flex items-center gap-3 rounded-xl border p-3 cursor-pointer transition-colors',
        scope === value ? 'border-trace-blue bg-trace-blue/[0.06]' : 'border-line hover:bg-elevated'
      )}
    >
      <Radio value={value} checked={scope === value} onChange={v => setScope(v as PrintScope)} />
      <span className="flex-1 min-w-0">
        <span className="block text-body font-semibold text-primary">{label}</span>
        <span className="block text-small text-muted">{hint}</span>
      </span>
      <span className="text-h4 font-mono text-primary">{n}</span>
    </label>
  )

  return (
    <Modal title={t('fulfill.printBatch.title')} onClose={() => onClose(false)}>
      <div className="space-y-4" data-testid="print-batch-dialog">
        <p className="text-small text-muted">{t('fulfill.printBatch.intro', { count: allCount })}</p>

        <div className="space-y-2">
          <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.which')}</p>
          {scopeOption('new', t('fulfill.printBatch.newOnly'), t('fulfill.printBatch.newOnlyHint'), newCount)}
          {scopeOption('all', t('fulfill.printBatch.everything'),
            t('fulfill.printBatch.everythingHint', { count: allCount - newCount }), allCount)}
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-3">
          <div className="space-y-1.5">
            <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.paper')}</p>
            <SegmentedControl
              value={paper}
              onChange={v => setPaper(v as PrintPaper)}
              options={[
                { value: 'A6', label: t('fulfill.printBatch.paperA6') },
                { value: 'A4', label: 'A4' },
              ]}
            />
          </div>
          <div className="space-y-1.5">
            <p className="text-small font-semibold text-primary">{t('fulfill.printBatch.order')}</p>
            <SegmentedControl
              value={sort}
              onChange={v => setSort(v as PrintSort)}
              options={[
                { value: 'oldest', label: t('fulfill.printBatch.oldest') },
                { value: 'newest', label: t('fulfill.printBatch.newest') },
              ]}
            />
          </div>
        </div>

        <Checkbox
          checked={pickList}
          onChange={setPickList}
          label={
            <span>
              <span className="block text-body text-primary">{t('fulfill.printBatch.pickList')}</span>
              <span className="block text-small text-muted">{t('fulfill.printBatch.pickListHint')}</span>
            </span>
          }
        />

        {lastJob && lastJob.printedCount > 0 && (
          <div className="flex flex-wrap items-center gap-2 rounded-xl border border-line p-3" data-testid="last-print">
            <span className="flex-1 min-w-0 text-small text-muted">
              {t('fulfill.printBatch.lastPrint', {
                printed: lastJob.printedCount,
                time: new Date(lastJob.finishedAt ?? lastJob.createdAt)
                  .toLocaleTimeString(i18n.language, { hour: '2-digit', minute: '2-digit' }),
              })}
            </span>
            {lastJob.pdfAvailable && (
              <Button size="sm" variant="secondary" onClick={() => { openJobPdf(lastJob.jobId).catch(() => {}) }}>
                {t('fulfill.printBatch.openWaybills')}
              </Button>
            )}
            <Button size="sm" variant="ghost" onClick={() => setJobId(lastJob.jobId)}>
              {t('fulfill.printBatch.showLastPrint')}
            </Button>
          </div>
        )}

        {error && <Alert tone="critical" title={error} />}

        <div className="flex gap-2 pt-1">
          <Button variant="secondary" className="flex-1" onClick={() => onClose(false)} disabled={starting}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" iconStart={Printer} loading={starting} disabled={count === 0} onClick={print}>
            {t('fulfill.printBatch.printN', { count })}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
