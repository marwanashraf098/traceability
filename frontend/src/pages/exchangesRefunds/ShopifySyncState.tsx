import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ExternalLink } from 'lucide-react'
import {
  ReturnRequestDetail, TransferCommandError, checkShopifySync, markShopifySyncChecked, retryShopifySync,
} from '../../api'
import { Button, useToast } from '../../components/ui'

const REASONS = new Set([
  'pre_connect_order', 'not_a_shopify_order', 'store_not_connected', 'no_permission', 'nothing_to_return',
  'line_not_in_shopify', 'too_many_lines', 'not_fulfilled_in_shopify', 'not_returnable_in_shopify', 'request_ended',
  'exchange_not_yet', 'readback_mismatch', 'not_found_in_shopify', 'ended_in_shopify', 'several_returns_found',
])

/**
 * Shopify-native returns S5a — the request drawer's "Shopify" row: is this request's return in Shopify, and what a
 * person can do when it isn't sure (Try again / Check again / I checked it in Shopify). Rendered only when the backend
 * sends `shopifySync` (listed tenants with the feature on). Owner / manager only, like the drawer.
 */
export default function ShopifySyncState({ detail, onChanged }: {
  detail: ReturnRequestDetail
  onChanged: () => Promise<void>
}) {
  const { t, i18n } = useTranslation()
  const { toast } = useToast()
  const [busy, setBusy] = useState<'retry' | 'check' | 'checked' | null>(null)
  // A refused action's reason, shown in the row itself (not only in a toast) — never a silent no-op.
  const [refused, setRefused] = useState<string | null>(null)
  const s = detail.shopifySync
  if (!s) return null
  const k = 'exchangesRefunds.requests.drawer.shopify'
  const reason = s.reason && REASONS.has(s.reason) ? t(`${k}.reasons.${s.reason}`) : null

  async function run(kind: 'retry' | 'check' | 'checked') {
    setBusy(kind)
    setRefused(null)
    try {
      if (kind === 'retry') await retryShopifySync(detail.id)
      else if (kind === 'check') await checkShopifySync(detail.id)
      else await markShopifySyncChecked(detail.id)
      toast({ tone: 'success', message: t(kind === 'checked' ? `${k}.checkedDone` : `${k}.started`) })
      await onChanged()
    } catch (e) {
      const message = e instanceof TransferCommandError
        ? (i18n.language === 'ar' ? e.messageAr : e.messageEn)
        : t(`${k}.actionFailed`)
      setRefused(message)
      toast({ tone: 'error', message })
    } finally {
      setBusy(null)
    }
  }

  const link = s.adminUrl ? (
    <a href={s.adminUrl} target="_blank" rel="noreferrer"
      className="inline-flex items-center gap-1 text-small font-medium text-trace-blue hover:underline">
      {t(`${k}.openInShopify`)}<ExternalLink className="w-3 h-3" aria-hidden="true" />
    </a>
  ) : null

  let line: JSX.Element
  if (s.status === 'created' || s.status === 'resolved') {
    line = (
      <p data-testid="shopify-sync-created">
        {s.returnName
          ? <>{t(`${k}.created`)} · <bdi dir="ltr" className="font-medium">{s.returnName}</bdi></>
          : t(`${k}.createdNoName`)}
      </p>
    )
  } else if (s.status === 'waiting' || s.status === 'pending') {
    line = <p className="text-muted" data-testid="shopify-sync-waiting">
      {t(s.reason === 'awaiting_approval' ? `${k}.waitingApproval` : s.status === 'pending' ? `${k}.pending` : `${k}.waiting`)}
    </p>
  } else if (s.status === 'skipped') {
    line = <p className="text-muted" data-testid="shopify-sync-skipped">
      {t(`${k}.skipped`)}{reason ? ` — ${reason}` : ''}
    </p>
  } else if (s.status === 'failed') {
    line = <p className={s.attemptsExhausted ? 'text-critical' : 'text-muted'} data-testid="shopify-sync-failed">
      {t(s.attemptsExhausted ? `${k}.failedExhausted` : `${k}.failed`)}
    </p>
  } else if (s.status === 'failed_ambiguous') {
    line = <p className="text-warning-text" data-testid="shopify-sync-ambiguous">{t(`${k}.ambiguous`)}</p>
  } else {
    line = <p className="text-warning-text" data-testid="shopify-sync-review">
      {t(`${k}.review`)}{reason ? ` — ${reason}` : ''}
    </p>
  }

  return (
    <div className="space-y-1.5" data-testid="shopify-sync">
      {line}
      {s.outOfStep && <p className="text-small text-warning-text" data-testid="shopify-sync-out-of-step">{t(`${k}.outOfStep`)}</p>}
      {s.actionsNeedCheck && <p className="text-small text-warning-text" data-testid="shopify-sync-step-check">{t(`${k}.stepNeedsCheck`)}</p>}
      {link}
      {refused && <p role="alert" className="text-small text-critical" data-testid="shopify-sync-refused">{refused}</p>}
      {(s.canRetry || s.status === 'failed_ambiguous' || s.canMarkChecked) && (
        <div className="flex flex-wrap gap-2">
          {s.status === 'failed_ambiguous' && (
            <Button size="sm" variant="outline" loading={busy === 'check'} disabled={busy != null} onClick={() => run('check')}>
              {t(`${k}.checkAgain`)}
            </Button>
          )}
          {s.canRetry && (
            <Button size="sm" variant="outline" loading={busy === 'retry'} disabled={busy != null} onClick={() => run('retry')}>
              {t(s.status === 'failed_ambiguous' ? `${k}.notInShopifyRetry` : `${k}.retry`)}
            </Button>
          )}
          {s.canMarkChecked && (
            <Button size="sm" variant="outline" loading={busy === 'checked'} disabled={busy != null} onClick={() => run('checked')}>
              {t(`${k}.markChecked`)}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}
