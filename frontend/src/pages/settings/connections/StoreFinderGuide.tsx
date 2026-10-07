import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CheckCircle2, ExternalLink, Info, Smartphone } from 'lucide-react'
import { Button, Input } from '../../../components/ui'
import { recogniseStoreAddress } from './storeAddress'
import { StepBody, WizardHeader } from './WizardStep'

// "Where do I find this?" (design/Traced_shopify_connect_wizard_dc.html, W1–W4): three steps — open the
// Shopify admin, copy the address bar, paste it here — plus the "On your phone?" alternative (the Shopify
// app has no address bar: Settings › Domains shows the .myshopify.com address). Step 3 hands the pasted
// value back to the finder, which resolves it exactly like the card's own input.

const TOTAL = 3

function BrowserIllustration({ highlight }: { highlight: boolean }) {
  return (
    <div className="rounded-xl border border-line overflow-hidden bg-panel" aria-hidden="true" dir="ltr">
      <div className="flex items-center gap-2 px-2.5 py-2 bg-elevated border-b border-line">
        <span className="flex gap-1.5">
          <i className="block w-2 h-2 rounded-full bg-line" /><i className="block w-2 h-2 rounded-full bg-line" />
          <i className="block w-2 h-2 rounded-full bg-line" />
        </span>
        <span className={`flex-1 min-w-0 truncate font-mono text-[12px] rounded-md px-2.5 py-1 border ${
          highlight ? 'border-brand bg-brand/5 ring-[3px] ring-brand/20 text-primary' : 'border-line bg-panel text-primary'}`}>
          {highlight ? <><mark className="bg-brand/20 text-primary rounded-sm">admin.shopify.com/store/abc123</mark>/orders</> : 'admin.shopify.com/store/abc123'}
        </span>
      </div>
      <div className="flex min-h-[84px]">
        <div className="w-20 bg-[#1a1a1a] p-2.5 space-y-1.5">
          {[0, 1, 2, 3].map(i => <i key={i} className={`block h-1.5 rounded ${i === (highlight ? 1 : 0) ? 'bg-[#6b6b6b]' : 'bg-[#3a3a3a]'}`} />)}
        </div>
        <div className="flex-1 p-3 space-y-2">
          {['40%', '85%', '65%'].map(w => <i key={w} className="block h-2 rounded bg-elevated" style={{ width: w }} />)}
        </div>
      </div>
    </div>
  )
}

function PhoneIllustration() {
  const { t } = useTranslation()
  return (
    <div className="w-56 mx-auto rounded-[22px] border border-line bg-panel shadow-e1 px-2 pt-2.5 pb-3.5" aria-hidden="true">
      <p className="text-center text-[12px] font-semibold text-primary pb-2.5 border-b border-line">
        {t('connections.shopify.guide.phoneScreen')}
      </p>
      <p className="text-[10.5px] text-muted px-1.5 pt-2.5 pb-1">{t('connections.shopify.guide.phoneShopifyDomain')}</p>
      <div className="mx-0.5 rounded-lg border border-brand bg-brand/[0.04] ring-[3px] ring-brand/15 px-2.5 py-2" dir="ltr">
        <p className="font-mono text-[11.5px] text-primary">abc123.myshopify.com</p>
      </div>
      <p className="text-[10.5px] text-muted px-1.5 pt-2.5 pb-1">{t('connections.shopify.guide.phoneOtherDomains')}</p>
      <div className="mx-0.5 rounded-lg border border-line px-2.5 py-2" dir="ltr">
        <p className="font-mono text-[11.5px] text-muted">yourbrand.com</p>
      </div>
    </div>
  )
}

function Tip({ children }: { children: React.ReactNode }) {
  return (
    <p className="flex items-start gap-2 rounded-lg bg-elevated px-3 py-2.5 text-small text-muted">
      <Info size={15} className="flex-shrink-0 mt-0.5" /><span>{children}</span>
    </p>
  )
}

export default function StoreFinderGuide({ onClose, onUse }: {
  onClose: () => void
  onUse: (value: string) => void
}) {
  const { t } = useTranslation()
  const [step, setStep] = useState(1)
  const [phone, setPhone] = useState(false)
  const [pasted, setPasted] = useState('')
  const recognised = recogniseStoreAddress(pasted)
  const title = t('connections.shopify.guide.title')

  if (phone) {
    return (
      <div className="space-y-4" data-testid="store-finder-guide-phone">
        <h3 className="text-h4 text-primary">{title}</h3>
        <p className="flex items-center gap-3 text-small font-semibold uppercase tracking-wide text-muted before:flex-1 before:h-px before:bg-line after:flex-1 after:h-px after:bg-line">
          {t('connections.shopify.guide.phoneDivider')}
        </p>
        <StepBody title={t('connections.shopify.guide.phoneTitle')} body={t('connections.shopify.guide.phoneBody')} />
        <PhoneIllustration />
        <Tip>{t('connections.shopify.guide.phoneTip')}</Tip>
        <div className="flex gap-2.5 pt-1">
          <Button variant="secondary" onClick={() => setPhone(false)}>{t('connections.shopify.guide.back')}</Button>
          <Button variant="primary" className="flex-1" onClick={() => { setPhone(false); setStep(3) }}>
            {t('connections.shopify.guide.haveIt')}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4" data-testid="store-finder-guide">
      <WizardHeader title={title} step={step} total={TOTAL}
        stepLabel={t('connections.shopify.wizard.stepLabel', { current: step, total: TOTAL })} />

      <div className="min-h-[140px]">
        {step === 1 && (
          <StepBody title={t('connections.shopify.guide.s1Title')} body={t('connections.shopify.guide.s1Body')}>
            <a className="inline-flex items-center gap-1.5 text-small font-medium text-brand hover:underline"
               href="https://admin.shopify.com" target="_blank" rel="noopener noreferrer">
              <ExternalLink size={15} />{t('connections.shopify.guide.openAdmin')}
            </a>
            <BrowserIllustration highlight={false} />
            <button type="button" onClick={() => setPhone(true)}
                    className="inline-flex items-center gap-1.5 text-small font-medium text-brand hover:underline">
              <Smartphone size={15} />{t('connections.shopify.guide.phoneLink')}
            </button>
          </StepBody>
        )}
        {step === 2 && (
          <StepBody title={t('connections.shopify.guide.s2Title')} body={t('connections.shopify.guide.s2Body')}>
            <BrowserIllustration highlight />
            <Tip>{t('connections.shopify.guide.s2Tip')}</Tip>
          </StepBody>
        )}
        {step === 3 && (
          <StepBody title={t('connections.shopify.guide.s3Title')} body={t('connections.shopify.guide.s3Body')}>
            <div>
              <Input
                aria-label={t('connections.shopify.finder.label')}
                value={pasted}
                onChange={e => setPasted(e.target.value)}
                placeholder={t('connections.shopify.finder.placeholder')}
                dir="ltr"
                autoComplete="off"
                spellCheck={false}
              />
              {recognised && (
                <p className="flex items-center gap-2 mt-2 text-small text-success">
                  <CheckCircle2 size={15} />
                  {t(recognised.source === 'admin_link' ? 'connections.shopify.finder.recognisedAdmin' : 'connections.shopify.finder.recognisedAddress')}
                  <span className="text-muted rtl:-scale-x-100" aria-hidden="true">→</span>
                  <span className="font-mono text-primary" dir="ltr">{recognised.shopDomain}</span>
                </p>
              )}
            </div>
          </StepBody>
        )}
      </div>

      <div className="flex gap-2.5 pt-1">
        <Button variant="secondary" onClick={() => (step === 1 ? onClose() : setStep(s => s - 1))}>
          {t('connections.shopify.guide.back')}
        </Button>
        {step < TOTAL ? (
          <Button variant="primary" className="flex-1" onClick={() => setStep(s => s + 1)}>
            {t('connections.shopify.guide.next')}
          </Button>
        ) : (
          <Button variant="primary" className="flex-1" disabled={!pasted.trim()} onClick={() => onUse(pasted)}>
            {t('connections.shopify.finder.find')}
          </Button>
        )}
      </div>
    </div>
  )
}
