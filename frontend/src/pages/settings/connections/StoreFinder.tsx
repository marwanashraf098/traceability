import { useEffect, useState, FormEvent, ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { ArrowRight, CheckCircle2, HelpCircle, Info, Search, XCircle } from 'lucide-react'
import { Button, Input } from '../../../components/ui'
import { shopifyInitiate, shopifyResolveStore, TransferCommandError } from '../../../api'
import { looksLikeDomain, recogniseStoreAddress, StoreSource } from './storeAddress'
import StoreFinderGuide from './StoreFinderGuide'

// "Find your store" (design/Traced_shopify_connect_wizard_dc.html, A1 / B / D / E): a never-connected
// tenant pastes a .myshopify.com address or a Shopify admin link, sees "We found your store", confirms,
// and is handed to Shopify through /oauth/initiate (which runs the same-shop rule and the store typo
// check server-side — only a clear not-found comes back as STORE_NOT_FOUND). The recognition shown while
// typing is a preview; POST /shopify/resolve-store is the source of truth.

type FinderError =
  | { kind: 'notAddress' }
  | { kind: 'notShopify' }
  | { kind: 'notFound'; shop: string }
  | { kind: 'message'; text: string }

export function StoreNotFoundAlert({ shop, actions }: { shop: string; actions?: ReactNode }) {
  const { t } = useTranslation()
  return (
    <ErrorAlert title={<>{t('connections.shopify.finder.errNotFoundTitle')} <span className="font-mono">{shop}</span>.</>}
                body={t('connections.shopify.finder.errNotFoundBody')} actions={actions} />
  )
}

export function ErrorAlert({ title, body, actions }: { title: ReactNode; body?: ReactNode; actions?: ReactNode }) {
  return (
    <div role="alert" className="flex items-start gap-3 rounded-xl border border-danger/25 bg-danger/[0.06] p-3">
      <XCircle size={18} className="text-danger flex-shrink-0 mt-0.5" />
      <div className="flex-1 min-w-0 space-y-1">
        <p className="text-small font-semibold text-primary">{title}</p>
        {body && <p className="text-small text-muted">{body}</p>}
        {actions && <div className="flex flex-wrap gap-x-4 gap-y-1 pt-1">{actions}</div>}
      </div>
    </div>
  )
}

function linkClass() {
  return 'inline-flex items-center gap-1.5 text-small font-medium text-brand hover:underline'
}

export default function StoreFinder({ initialInput, onNavigate }: {
  initialInput?: string
  /** Leaves the page for Shopify's approval screen — window.location by default; a seam for tests. */
  onNavigate?: (url: string) => void
}) {
  const { t, i18n } = useTranslation()
  const isAr = i18n.language === 'ar'
  const [input, setInput] = useState(initialInput ?? '')
  const [found, setFound] = useState<{ shopDomain: string; source: StoreSource } | null>(null)
  const [busy, setBusy] = useState<'resolve' | 'connect' | null>(null)
  const [error, setError] = useState<FinderError | null>(null)
  const [guide, setGuide] = useState(false)

  useEffect(() => {
    if (initialInput) setInput(prev => prev || initialInput)
  }, [initialInput])

  const recognised = recogniseStoreAddress(input)
  const navigate = onNavigate ?? ((url: string) => { window.location.href = url })

  async function find(value: string = input) {
    setError(null)
    if (!value.trim()) return
    setBusy('resolve')
    try {
      const res = await shopifyResolveStore(value)
      setFound(res)
    } catch (err) {
      if (err instanceof TransferCommandError && err.code === 'NOT_SHOPIFY_ADDRESS') {
        setError({ kind: looksLikeDomain(value) ? 'notShopify' : 'notAddress' })
      } else {
        setError({ kind: 'message', text: messageOf(err) })
      }
    } finally {
      setBusy(null)
    }
  }

  async function connect() {
    if (!found) return
    setError(null)
    setBusy('connect')
    try {
      const res = await shopifyInitiate(found.shopDomain)
      navigate(res.consentUrl)
    } catch (err) {
      if (err instanceof TransferCommandError && err.code === 'STORE_NOT_FOUND') {
        setError({ kind: 'notFound', shop: found.shopDomain })
      } else {
        setError({ kind: 'message', text: messageOf(err) })
      }
      setBusy(null)
    }
  }

  function messageOf(err: unknown) {
    if (err instanceof TransferCommandError) return isAr ? err.messageAr : err.messageEn
    return t('connections.shopify.error')
  }

  function notMine() {   // back to the input, text kept
    setFound(null)
    setError(null)
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    void find()
  }

  if (guide) {
    return (
      <StoreFinderGuide
        onClose={() => setGuide(false)}
        onUse={value => { setInput(value); setGuide(false); setFound(null); void find(value) }}
      />
    )
  }

  const whereLink = (
    <button type="button" className={linkClass()} onClick={() => setGuide(true)}>
      <HelpCircle size={15} />{t('connections.shopify.finder.where')}
    </button>
  )

  if (found) {
    return (
      <div className="space-y-4" data-testid="store-finder-confirm">
        <div className="rounded-xl border border-success/25 bg-success/[0.06] p-4 space-y-3">
          <p className="flex items-center gap-2 font-semibold text-success">
            <CheckCircle2 size={18} />{t('connections.shopify.finder.foundTitle')}
          </p>
          <p className="font-mono text-body-lg text-primary bg-panel border border-line rounded-lg px-3.5 py-2.5 break-all" dir="ltr"
             data-testid="store-finder-found">
            {found.shopDomain}
          </p>
          <p className="text-small text-muted">
            {t(found.source === 'admin_link' ? 'connections.shopify.finder.fromAdmin' : 'connections.shopify.finder.fromAddress')}
          </p>
        </div>

        {error?.kind === 'notFound' && (
          <StoreNotFoundAlert shop={error.shop} actions={<>
            <button type="button" className={linkClass()} onClick={notMine}>{t('connections.shopify.finder.changeAddress')}</button>
            {whereLink}
          </>} />
        )}
        {error?.kind === 'message' && <ErrorAlert title={error.text} />}

        <div className="flex gap-2.5">
          <Button variant="ghost" onClick={notMine} disabled={busy === 'connect'}>
            {t('connections.shopify.finder.notMine')}
          </Button>
          <Button variant="primary" className="flex-1" onClick={() => void connect()} loading={busy === 'connect'}>
            {busy === 'connect' ? t('connections.shopify.finder.connecting') : t('connections.shopify.finder.connect')}
            {busy !== 'connect' && <ArrowRight size={16} className="rtl:-scale-x-100" />}
          </Button>
        </div>
        <p className="flex items-start gap-2 text-small text-muted">
          <Info size={15} className="flex-shrink-0 mt-0.5" />{t('connections.shopify.finder.next')}
        </p>
      </div>
    )
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3" data-testid="store-finder">
      <div>
        <h4 className="text-body-lg font-semibold text-primary">{t('connections.shopify.finder.title')}</h4>
        <p className="text-body text-muted">{t('connections.shopify.finder.body')}</p>
      </div>
      <div>
        <label htmlFor="storeFinderInput" className="block text-small text-muted font-medium mb-1.5">
          {t('connections.shopify.finder.label')}
        </label>
        <Input
          id="storeFinderInput"
          iconStart={Search}
          value={input}
          onChange={e => { setInput(e.target.value); setError(null) }}
          placeholder={t('connections.shopify.finder.placeholder')}
          invalid={error?.kind === 'notAddress' || error?.kind === 'notShopify'}
          dir="ltr"
          autoComplete="off"
          spellCheck={false}
          disabled={busy === 'resolve'}
        />
        {recognised ? (
          <p className="flex items-center gap-2 mt-2 text-small text-success" data-testid="store-finder-recognised">
            <CheckCircle2 size={15} />
            {t(recognised.source === 'admin_link' ? 'connections.shopify.finder.recognisedAdmin' : 'connections.shopify.finder.recognisedAddress')}
            <span className="text-muted rtl:-scale-x-100" aria-hidden="true">→</span>
            <span className="font-mono text-primary" dir="ltr">{recognised.shopDomain}</span>
          </p>
        ) : (
          <div className="flex flex-wrap gap-1.5 mt-2" dir="ltr">
            <span className="font-mono text-[11.5px] bg-elevated border border-line rounded-full px-2 py-0.5 text-muted">abc123.myshopify.com</span>
            <span className="font-mono text-[11.5px] bg-elevated border border-line rounded-full px-2 py-0.5 text-muted">admin.shopify.com/store/abc123</span>
          </div>
        )}
      </div>

      {error?.kind === 'notAddress' && (
        <ErrorAlert title={t('connections.shopify.finder.errNotAddressTitle')}
                    body={t('connections.shopify.finder.errNotAddressBody')} actions={whereLink} />
      )}
      {error?.kind === 'notShopify' && (
        <ErrorAlert title={t('connections.shopify.finder.errNotShopifyTitle')}
                    body={t('connections.shopify.finder.errNotShopifyBody')} actions={whereLink} />
      )}
      {error?.kind === 'message' && <ErrorAlert title={error.text} />}

      <Button type="submit" variant="primary" className="w-full" disabled={!input.trim()} loading={busy === 'resolve'}>
        {t('connections.shopify.finder.find')}
      </Button>
      {whereLink}
    </form>
  )
}
