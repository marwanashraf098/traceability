import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Download, ExternalLink } from 'lucide-react'
import { fetchPrintJobPdf, getPrintJob, isPrintJobActive, PrintJob } from '../../api'
import { Alert, Button, Progress, Spinner } from '../../components/ui'

// Merged-PDF slice C — one print job's progress and result, shared by the print dialog (queue mode and
// waybill mode) and the reprint list. The job runs on the server; this only follows it, so closing the
// dialog never stops a print (the dialog finds it again through GET /print-jobs/latest).

let pollMs = 1500
/** Tests only: poll faster. */
export function setPrintJobPollMs(ms: number) { pollMs = ms }

/** Follows one job: polls while it is queued / running, stops when it ends or the component unmounts. */
export function usePrintJob(jobId: string | null) {
  const [job, setJob] = useState<PrintJob | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    if (!jobId) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const tick = () => {
      getPrintJob(jobId)
        .then(j => {
          if (cancelled) return
          setJob(j)
          setError(false)
          if (j && isPrintJobActive(j)) timer = setTimeout(tick, pollMs)
        })
        .catch(() => {
          if (cancelled) return
          setError(true)
          timer = setTimeout(tick, pollMs * 2)
        })
    }
    tick()
    return () => { cancelled = true; if (timer) clearTimeout(timer) }
  }, [jobId])
  return { job, error }
}

/**
 * Opens the job's PDF in a new tab. Must be called from the click handler itself: the tab is opened
 * synchronously (popup blockers allow only that), then filled once the PDF has downloaded.
 */
export function openJobPdf(jobId: string) {
  const win = window.open('', '_blank')
  return fetchPrintJobPdf(jobId).then(({ blob }) => {
    const url = URL.createObjectURL(blob)
    if (win) win.location.href = url
    else window.open(url, '_blank')
  }).catch(e => { win?.close(); throw e })
}

export async function downloadJobPdf(jobId: string) {
  const { blob, filename } = await fetchPrintJobPdf(jobId, true)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** Why a waybill wasn't printed — shared by the print dialog and the reprint result. */
export function printSkipReasonText(t: TFunction, reason: string) {
  if (reason === 'DELETED_IN_BOSTA') return t('fulfill.printBatch.reason.deletedInBosta')
  if (reason === 'BOSTA_REJECTED_UNISOLATED') return t('fulfill.printBatch.reason.unisolated')
  if (reason === 'BOSTA_BUSY') return t('fulfill.printBatch.reason.busy')
  if (reason === 'BOSTA_TEMPORARY') return t('fulfill.printBatch.reason.temporary')
  if (reason === 'BOSTA_AUTH') return t('fulfill.printBatch.reason.auth')
  if (reason === 'PRINT_INTERRUPTED') return t('fulfill.printBatch.reason.interrupted')
  // Only a reprint skips cancelled orders (a print's candidates never include them).
  if (reason === 'ORDER_CANCELLED') return t('fulfill.waybill.lists.skippedCancelled')
  if (reason === 'BOSTA_EMAIL_PATH') return t('fulfill.printBatch.reason.emailed')
  if (reason === 'UNLINKED') return t('fulfill.printBatch.reason.unlinked')
  if (reason === 'NON_PRINTABLE_TYPE:CRP') return t('fulfill.printBatch.reason.returnPickup')
  if (reason.startsWith('NON_PRINTABLE_STATE:')) return t('fulfill.printBatch.reason.notPrintable')
  if (reason.startsWith('BOSTA_REJECTED')) return t('fulfill.printBatch.reason.rejected')
  return reason
}

/** Not printed for a reason that isn't the waybill's fault (Bosta busy / no answer / key, print stopped). */
export function isRetryReason(reason: string) {
  return reason === 'BOSTA_BUSY' || reason === 'BOSTA_TEMPORARY' || reason === 'BOSTA_AUTH'
    || reason === 'PRINT_INTERRUPTED'
}

/** "Printed N · Skipped M (X deleted in Bosta / Y rejected / Z to print again)". */
export function printSummaryText(t: TFunction, printed: number, excluded: { reason: string }[]) {
  const deleted = excluded.filter(x => x.reason === 'DELETED_IN_BOSTA').length
  const retry = excluded.filter(x => isRetryReason(x.reason)).length
  const rejected = excluded.length - deleted - retry
  const parts = [
    deleted > 0 ? t('fulfill.printBatch.skippedDeleted', { count: deleted }) : null,
    rejected > 0 ? t('fulfill.printBatch.skippedRejected', { count: rejected }) : null,
    retry > 0 ? t('fulfill.printBatch.skippedRetry', { count: retry }) : null,
  ].filter(Boolean)
  return `${t('fulfill.printBatch.summary', { printed, skipped: excluded.length })} (${parts.join(' / ')})`
}

/**
 * Progress while the job runs ("Waiting for another print to finish" / "Preparing waybills… 98 of 245"),
 * then the result: printed / skipped summary, every skipped waybill with its reason, Open waybills (a new
 * tab, opened inside the click) and Download. {@code actions} renders the caller's own buttons (Done, pick list).
 */
export function PrintJobPanel({
  jobId,
  alreadyRunning = false,
  actions,
  onJob,
}: {
  jobId: string
  alreadyRunning?: boolean
  actions: (job: PrintJob | null) => React.ReactNode
  /** Every update of the job (e.g. to refresh the caller's lists once it ends). */
  onJob?: (job: PrintJob) => void
}) {
  const { t, i18n } = useTranslation()
  const { job, error } = usePrintJob(jobId)
  const [pdfError, setPdfError] = useState(false)

  useEffect(() => { if (job) onJob?.(job) }, [job]) // eslint-disable-line react-hooks/exhaustive-deps

  const open = () => { setPdfError(false); openJobPdf(jobId).catch(() => setPdfError(true)) }
  const download = () => { setPdfError(false); downloadJobPdf(jobId).catch(() => setPdfError(true)) }

  if (!job || isPrintJobActive(job)) {
    const queued = job?.status === 'queued'
    const total = job?.total ?? 0
    const done = job?.done ?? 0
    return (
      <div className="space-y-4" data-testid="print-job-progress">
        {alreadyRunning && <Alert tone="info" title={t('fulfill.printBatch.alreadyRunning')} />}
        <div className="flex items-center gap-3">
          <Spinner size={18} />
          <p className="text-body text-primary" data-testid="print-job-status" aria-live="polite">
            {!job ? t('fulfill.printBatch.starting')
              : queued ? t('fulfill.printBatch.queued')
              : t('fulfill.printBatch.preparing', { done, total })}
          </p>
        </div>
        <Progress value={!job || queued || total === 0 ? undefined : (done / total) * 100} />
        {error && <Alert tone="warning" title={t('fulfill.printBatch.error')} />}
        <p className="text-small text-muted">{t('fulfill.printBatch.keepsGoing')}</p>
        <div className="flex flex-wrap gap-2 pt-1">{actions(job)}</div>
      </div>
    )
  }

  const message = job.message ? (i18n.language === 'ar' ? job.message.messageAr : job.message.messageEn) : null
  const showMessage = message && job.message?.code !== 'BOSTA_BUSY'      // busy has its own alert below
  return (
    <div className="space-y-4" data-testid="print-batch-result">
      {job.printedCount > 0 ? (
        <p className="text-body text-primary">
          {job.kind === 'reprint'
            ? t('fulfill.waybill.lists.reprinted', { count: job.printedCount })
            : t('fulfill.printBatch.printed', { no: job.batchNo, count: job.printedCount })}
        </p>
      ) : (
        <p className="text-body text-primary">
          {job.candidateCount === 0 && job.kind === 'print'
            ? t('fulfill.printBatch.nothingReady') : t('fulfill.printBatch.nothingPrinted')}
        </p>
      )}
      {showMessage && <Alert tone={job.status === 'done' ? 'info' : 'critical'} title={message} />}
      {job.excluded.some(x => x.reason === 'BOSTA_BUSY') && (
        <Alert tone="warning" title={t('fulfill.printBatch.busyAlert')} />
      )}
      {job.remainingCount > 0 && (
        <Alert tone="info" title={t('fulfill.printBatch.moreReady', { count: job.remainingCount })} />
      )}
      {job.printedCount > 0 && job.orderGuaranteed === false && (
        <Alert tone="warning" title={t('fulfill.printBatch.orderNotGuaranteed')} />
      )}
      {job.printedCount > 0 && !job.pdfAvailable && (
        <Alert tone="info" title={t('fulfill.printBatch.pdfGone')} />
      )}
      {job.excluded.length > 0 && (
        <div>
          <p className="text-small font-semibold text-primary mb-1.5" data-testid="print-batch-summary">
            {printSummaryText(t, job.printedCount, job.excluded)}
          </p>
          <ul className="max-h-40 overflow-y-auto space-y-1">
            {job.excluded.map(x => (
              <li key={x.trackingNumber} className="text-small text-muted flex flex-wrap gap-x-2">
                {x.orderNumber && <span className="font-mono text-primary">{x.orderNumber}</span>}
                <span className="font-mono text-primary">{x.trackingNumber}</span>
                <span>{printSkipReasonText(t, x.reason)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
      {pdfError && <Alert tone="critical" title={t('fulfill.printBatch.error')} />}
      {job.pdfAvailable && (
        <div className="flex flex-wrap gap-2">
          <Button className="flex-1 min-w-[10rem]" iconStart={ExternalLink} onClick={open}>
            {t('fulfill.printBatch.openWaybills')}
          </Button>
          <Button variant="secondary" className="flex-1 min-w-[10rem]" iconStart={Download} onClick={download}>
            {t('fulfill.printBatch.download')}
          </Button>
        </div>
      )}
      <div className="flex flex-wrap gap-2 pt-1">{actions(job)}</div>
    </div>
  )
}
