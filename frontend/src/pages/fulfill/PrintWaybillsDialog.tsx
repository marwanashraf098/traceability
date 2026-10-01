import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Printer } from 'lucide-react'
import {
  getPrintBatchOptions, printWaybillBatch, TransferCommandError,
  PrintBatchResult, PrintPaper, PrintScope, PrintSort,
} from '../../api'
import { Alert, Button, Checkbox, Modal, Radio, SegmentedControl, cn } from '../../components/ui'

// Pick & Pack S2 — "Print waybills" (design/pick-pack-waybill-mockup/PrintDialog.html for flow
// and copy). Counts come from the queue already on screen, so opening the page makes no extra
// request; the paper default is fetched only when this dialog opens.

/** Same cap as the server (PackPrintBatchService.MAX_WAYBILLS_PER_PRINT): from 50 waybills up
 *  Bosta emails the labels instead of returning the PDF, so one print = at most 49. */
const MAX_WAYBILLS_PER_PRINT = 49

function openPdf(win: Window | null, base64: string) {
  const bytes = atob(base64)
  const arr = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i)
  const url = URL.createObjectURL(new Blob([arr], { type: 'application/pdf' }))
  if (win) win.location.href = url
  else window.open(url, '_blank')
}

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
  const [scope, setScope] = useState<PrintScope>(newCount > 0 ? 'new' : 'all')
  const [paper, setPaper] = useState<PrintPaper>('A4')
  const [sort, setSort] = useState<PrintSort>('oldest')
  const [pickList, setPickList] = useState(false)
  const [printing, setPrinting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<PrintBatchResult | null>(null)

  useEffect(() => {
    let cancelled = false
    getPrintBatchOptions()
      .then(o => { if (!cancelled && o?.defaultPaper) setPaper(o.defaultPaper) })
      .catch(() => { /* keep A4 */ })
    return () => { cancelled = true }
  }, [])

  // The options show the real totals; the button says how many this print will actually send.
  const count = Math.min(scope === 'new' ? newCount : allCount, MAX_WAYBILLS_PER_PRINT)

  async function print() {
    if (printing || count === 0) return
    setPrinting(true)
    setError(null)
    // Open the tab inside the click so popup blockers allow it; filled once the PDF arrives.
    const win = window.open('', '_blank')
    try {
      const r = await printWaybillBatch({ scope, paper, sort })
      if (r.pdfBase64) openPdf(win, r.pdfBase64)
      else win?.close()
      setResult(r)
    } catch (e) {
      win?.close()
      setError(e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t('fulfill.printBatch.error'))
    } finally {
      setPrinting(false)
    }
  }

  function reasonText(reason: string) {
    if (reason === 'BOSTA_EMAIL_PATH') return t('fulfill.printBatch.reason.emailed')
    if (reason === 'UNLINKED') return t('fulfill.printBatch.reason.unlinked')
    if (reason === 'NON_PRINTABLE_TYPE:CRP') return t('fulfill.printBatch.reason.returnPickup')
    if (reason.startsWith('NON_PRINTABLE_STATE:')) return t('fulfill.printBatch.reason.notPrintable')
    if (reason.startsWith('BOSTA_REJECTED')) return t('fulfill.printBatch.reason.rejected')
    return reason
  }

  if (result) {
    return (
      <Modal title={t('fulfill.printBatch.title')} onClose={() => onClose(!!result.batchId)}>
        <div className="space-y-4" data-testid="print-batch-result">
          {result.batchId ? (
            <p className="text-body text-primary">
              {t('fulfill.printBatch.printed', { no: result.batchNo, count: result.waybillCount })}
            </p>
          ) : (
            <p className="text-body text-primary">
              {result.candidateCount === 0 ? t('fulfill.printBatch.nothingReady') : t('fulfill.printBatch.nothingPrinted')}
            </p>
          )}
          {result.batchId && result.remainingCount > 0 && (
            <Alert
              tone="info"
              title={t('fulfill.printBatch.remaining', { printed: result.waybillCount, count: result.remainingCount })}
            />
          )}
          {result.batchId && !result.orderGuaranteed && (
            <Alert tone="warning" title={t('fulfill.printBatch.orderNotGuaranteed')} />
          )}
          {result.excluded.length > 0 && (
            <div>
              <p className="text-small font-semibold text-primary mb-1.5">
                {t('fulfill.printBatch.excludedTitle', { count: result.excluded.length })}
              </p>
              <ul className="max-h-40 overflow-y-auto space-y-1">
                {result.excluded.map(x => (
                  <li key={x.trackingNumber} className="text-small text-muted flex flex-wrap gap-x-2">
                    <span className="font-mono text-primary">{x.orderNumber ?? x.trackingNumber}</span>
                    <span>{reasonText(x.reason)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="flex gap-2 pt-1">
            {pickList && result.batchId && (
              <Button
                variant="secondary"
                className="flex-1"
                onClick={() => window.open(`/fulfill/gather?batch=${result.batchId}`, '_blank')}
              >
                {t('fulfill.printBatch.openPickList')}
              </Button>
            )}
            <Button className="flex-1" onClick={() => onClose(!!result.batchId)}>{t('fulfill.printBatch.done')}</Button>
          </div>
        </div>
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

        {error && <Alert tone="critical" title={error} />}

        <div className="flex gap-2 pt-1">
          <Button variant="secondary" className="flex-1" onClick={() => onClose(false)} disabled={printing}>
            {t('common.cancel')}
          </Button>
          <Button className="flex-1" iconStart={Printer} loading={printing} disabled={count === 0} onClick={print}>
            {t('fulfill.printBatch.printN', { count })}
          </Button>
        </div>
      </div>
    </Modal>
  )
}
