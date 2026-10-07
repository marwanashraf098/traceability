import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, Store } from 'lucide-react'
import AuthLayout from '../../components/AuthLayout'
import { Button } from '../../components/ui'
import { shopifyPendingLinkConfirm, shopifyPendingLinkPreview, TransferCommandError } from '../../api'
import { ErrorAlert } from '../settings/connections/StoreFinder'

// Build D — /connect/shopify?link=<nonce>: the Traced side of "I already have a Traced account".
// The merchant arrives here in the TOP-LEVEL window from the embedded app (RequireAuth sends them
// through /login first and back). They confirm "Connect <shop> to <business>?"; the store is linked
// with the existing rules and the browser returns to the app inside Shopify admin. No typed domain,
// no /oauth/initiate. Expired / used link → "Start again from Shopify".

type State =
  | { kind: 'loading' }
  | { kind: 'confirm'; shop: string; business: string }
  | { kind: 'connecting'; shop: string; business: string }
  | { kind: 'done'; shop: string }
  | { kind: 'invalid' }
  | { kind: 'notOwner' }
  | { kind: 'error'; message: string }

export const SHOPIFY_ADMIN = 'https://admin.shopify.com'

export default function ConnectShopify({ onNavigate }: {
  /** Leaves Traced for Shopify admin — window.location by default; a seam for tests. */
  onNavigate?: (url: string) => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const link = new URLSearchParams(window.location.search).get('link') ?? ''
  const [state, setState] = useState<State>({ kind: 'loading' })
  const navigate = onNavigate ?? ((url: string) => { window.location.href = url })

  function fail(err: unknown) {
    if (err instanceof TransferCommandError) {
      if (err.code === 'PENDING_LINK_INVALID') return setState({ kind: 'invalid' })
      return setState({ kind: 'error', message: isAr ? err.messageAr : err.messageEn })
    }
    if (err instanceof Error && err.message.startsWith('403')) return setState({ kind: 'notOwner' })
    setState({ kind: 'error', message: t('connectShopify.genericError') })
  }

  useEffect(() => {
    if (!link) { setState({ kind: 'invalid' }); return }
    shopifyPendingLinkPreview(link)
      .then(p => setState({ kind: 'confirm', shop: p.shopDomain, business: p.businessName }))
      .catch(fail)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function confirm() {
    if (state.kind !== 'confirm') return
    setState({ kind: 'connecting', shop: state.shop, business: state.business })
    try {
      const res = await shopifyPendingLinkConfirm(link)
      setState({ kind: 'done', shop: res.shopDomain })
      navigate(res.redirectUrl)
    } catch (err) {
      fail(err)
    }
  }

  const startAgain = (
    <a href={SHOPIFY_ADMIN} className="inline-flex items-center gap-1.5 text-small font-medium text-brand hover:underline">
      {t('connectShopify.startAgain')}
    </a>
  )

  return (
    <AuthLayout>
      <div className="card p-6 space-y-4" data-testid="connect-shopify">
        <div>
          <h2 className="text-h3 text-primary">{t('connectShopify.title')}</h2>
        </div>

        {state.kind === 'loading' && (
          <p className="text-small text-muted" role="status">{t('connectShopify.loading')}</p>
        )}

        {(state.kind === 'confirm' || state.kind === 'connecting') && (
          <>
            <div className="flex items-center gap-3 rounded-xl border border-line bg-elevated px-3.5 py-3">
              <Store size={18} className="text-muted flex-shrink-0" />
              <span className="font-mono text-primary break-all" dir="ltr" data-testid="connect-shopify-shop">{state.shop}</span>
            </div>
            <p className="text-body text-primary">
              {t('connectShopify.question', { shop: state.shop, business: state.business })}
            </p>
            <p className="text-small text-muted">{t('connectShopify.explain')}</p>
            <div className="flex gap-2.5">
              <Button variant="ghost" onClick={() => navigate(SHOPIFY_ADMIN)} disabled={state.kind === 'connecting'}>
                {t('connectShopify.cancel')}
              </Button>
              <Button variant="primary" className="flex-1" onClick={() => void confirm()} loading={state.kind === 'connecting'}>
                {t('connectShopify.connect')}
              </Button>
            </div>
          </>
        )}

        {state.kind === 'done' && (
          <p className="flex items-center gap-2 font-semibold text-success" role="status">
            <CheckCircle2 size={18} />{t('connectShopify.done')}
          </p>
        )}

        {state.kind === 'invalid' && (
          <ErrorAlert title={t('connectShopify.invalidTitle')} body={t('connectShopify.invalidBody')} actions={startAgain} />
        )}
        {state.kind === 'notOwner' && (
          <ErrorAlert title={t('connectShopify.notOwnerTitle')} body={t('connectShopify.notOwnerBody')} />
        )}
        {state.kind === 'error' && <ErrorAlert title={state.message} actions={startAgain} />}
      </div>
    </AuthLayout>
  )
}
