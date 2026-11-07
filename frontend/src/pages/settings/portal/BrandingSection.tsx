import { useTranslation } from 'react-i18next'
import { CircleAlert } from 'lucide-react'
import type { PortalSettings } from '../../../api'
import { Input, cn } from '../../../components/ui'
import type { PortalFont } from '../../../portal/fonts'
import { FontPicker, LogoUploader, PortalPreview } from '../PortalBranding'
import { brandColourNote, policyWindowMismatch } from './portalChecks'
import { EditedTag, SectionCard } from './ui'

export const LOGO_PREFIX = 'https://cdn.shopify.com/'
export const POLICY_MAX = 2000
const HEX = /^#[0-9A-Fa-f]{6}$/

/** Same rule as the backend (and V103's CHECK): a Shopify Files CDN link. */
export function isValidLogoUrl(url: string): boolean {
  if (!url.startsWith(LOGO_PREFIX)) return false
  try {
    new URL(url)
    return true
  } catch {
    return false
  }
}

type Errors = Partial<Record<'logoUrl' | 'font' | 'brandColor' | 'policyText', string>>

/**
 * Branding & policy — the logo (saves on upload / remove, as before) and the Shopify Files link kept for stores that
 * use it, the font, the brand colour and the policy text (saved with "Save changes"), and the live preview. Two
 * client-only notes: what the portal does with the colour (portal/brand.ts, unchanged) and a policy text that names a
 * different number of days than the return window.
 */
export default function BrandingSection({
  saved, uploadedLogoUrl, onLogoSaved, logoUrl, onLogoUrl, font, onFont, brandColor, onBrandColor, policyText, onPolicyText,
  windowDays, errors, edited,
}: {
  saved: PortalSettings
  uploadedLogoUrl: string | null
  onLogoSaved: (s: PortalSettings) => void
  logoUrl: string
  onLogoUrl: (v: string) => void
  font: PortalFont
  onFont: (f: PortalFont) => void
  brandColor: string
  onBrandColor: (v: string) => void
  policyText: string
  onPolicyText: (v: string) => void
  windowDays: number | null
  errors: Errors
  edited: { logoUrl: boolean; font: boolean; brandColor: boolean; policyText: boolean }
}) {
  const { t } = useTranslation()
  const logo = logoUrl.trim()
  const color = brandColor.trim()
  const colourNote = brandColourNote(color)
  const mismatch = policyWindowMismatch(policyText, windowDays)

  return (
    <SectionCard id="portal-branding" title={t('settings.portal.sections.branding.title')}
      description={t('settings.portal.sections.branding.description')}>
      <div className="grid xl:grid-cols-[minmax(0,1fr)_360px]">
        <div className="divide-y divide-line min-w-0 xl:border-e border-line">
          <div className="px-6 py-5 space-y-2">
            <LogoUploader settings={saved} logoUrl={uploadedLogoUrl} onChange={onLogoSaved} />
            {/* The Shopify Files link (V103) — kept for stores already using it; an upload wins. */}
            <details className="group" open={!!saved.logoUrl} data-testid="logo-link-details">
              <summary className="text-small font-medium text-trace-blue cursor-pointer list-none [&::-webkit-details-marker]:hidden">
                {t('settings.portal.branding.useLink')}
              </summary>
              <div className="space-y-2 mt-2 max-w-xl">
                <div className="flex items-center">
                  <label htmlFor="portal-logo" className="text-small font-medium text-primary block">
                    {t('settings.portal.branding.logoLabel')}
                  </label>
                  {edited.logoUrl && <EditedTag />}
                </div>
                <div className="flex items-center gap-3">
                  <div className="flex-1" dir="ltr">
                    <Input
                      id="portal-logo"
                      type="url"
                      placeholder={`${LOGO_PREFIX}…`}
                      invalid={!!errors.logoUrl}
                      aria-invalid={!!errors.logoUrl}
                      value={logoUrl}
                      onChange={e => onLogoUrl(e.target.value)}
                    />
                  </div>
                  {isValidLogoUrl(logo) && (
                    <img
                      src={logo}
                      alt={t('settings.portal.branding.logoPreview')}
                      data-testid="logo-preview"
                      className="h-10 w-auto max-w-[120px] object-contain rounded-lg border border-line bg-surface p-1"
                    />
                  )}
                </div>
                {errors.logoUrl && <p className="text-small text-critical" role="alert" data-testid="error-logoUrl">{errors.logoUrl}</p>}
                <p className="text-small text-muted">{t('settings.portal.branding.logoHelp')} {t('settings.portal.branding.linkNote')}</p>
              </div>
            </details>
          </div>

          <div className="px-6 py-5 space-y-2">
            {edited.font && <EditedTag className="ms-0" />}
            <FontPicker value={font} onChange={onFont} />
            {errors.font && <p className="text-small text-critical" role="alert" data-testid="error-font">{errors.font}</p>}
          </div>

          <div className="px-6 py-5 space-y-2">
            <div className="flex items-center">
              <label htmlFor="portal-color" className="text-body font-semibold text-primary block">
                {t('settings.portal.branding.colorLabel')}
              </label>
              {edited.brandColor && <EditedTag />}
            </div>
            <div className="flex items-center gap-3">
              <input
                type="color"
                aria-label={t('settings.portal.branding.colorPicker')}
                className="h-10 w-12 rounded-lg border border-line bg-surface p-1 cursor-pointer"
                value={HEX.test(color) ? color.toLowerCase() : '#000000'}
                onChange={e => onBrandColor(e.target.value.toUpperCase())}
              />
              <div className="w-36">
                <Input
                  id="portal-color"
                  type="text"
                  placeholder="#1A2B3C"
                  dir="ltr"
                  className="font-mono"
                  maxLength={7}
                  invalid={!!errors.brandColor}
                  aria-invalid={!!errors.brandColor}
                  aria-describedby="portal-color-help"
                  value={brandColor}
                  onChange={e => onBrandColor(e.target.value)}
                />
              </div>
            </div>
            {errors.brandColor && <p className="text-small text-critical" role="alert" data-testid="error-brandColor">{errors.brandColor}</p>}
            <p id="portal-color-help" className="text-small text-muted">{t('settings.portal.branding.colorHelp')}</p>
            {colourNote && (
              <p className={cn('text-small', colourNote === 'fallback' ? 'text-warning-text' : 'text-muted')}
                data-testid={`colour-note-${colourNote}`}>
                {t(colourNote === 'fallback' ? 'settings.portal.branding.fallbackColour' : 'settings.portal.branding.lightColour')}
              </p>
            )}
          </div>

          <div className="px-6 py-5 space-y-2">
            <div className="flex items-center">
              <label htmlFor="portal-policy" className="text-body font-semibold text-primary block">
                {t('settings.portal.branding.policyLabel')}
              </label>
              {edited.policyText && <EditedTag />}
            </div>
            <textarea
              id="portal-policy"
              rows={5}
              dir="auto"
              maxLength={POLICY_MAX}
              aria-invalid={!!errors.policyText}
              aria-describedby="portal-policy-count"
              className={cn('input resize-y w-full', errors.policyText && 'border-critical')}
              value={policyText}
              onChange={e => onPolicyText(e.target.value)}
            />
            <div className="flex items-center justify-between gap-3">
              <span className="text-small text-critical" role={errors.policyText ? 'alert' : undefined} data-testid="error-policyText">
                {errors.policyText ?? ''}
              </span>
              <span id="portal-policy-count" className="text-small text-muted tabular-nums" dir="ltr">
                {policyText.length} / {POLICY_MAX}
              </span>
            </div>
            {mismatch && windowDays != null && (
              <p className="flex gap-2 items-start rounded-lg border border-warning/40 bg-warning/10 px-3 py-2.5 text-small text-warning-text"
                data-testid="policy-mismatch">
                <CircleAlert className="w-4 h-4 mt-px shrink-0" aria-hidden="true" />
                <span>{t('settings.portal.branding.policyMismatch', { quote: mismatch, count: windowDays })}</span>
              </p>
            )}
          </div>
        </div>

        <div className="px-6 py-5 bg-elevated/60 border-t xl:border-t-0 border-line">
          <PortalPreview
            font={font}
            brandColor={HEX.test(color) ? color : null}
            logoSrc={saved.logo ? uploadedLogoUrl : isValidLogoUrl(logo) ? logo : null}
            storeName={saved.storeName ?? ''}
            windowDays={windowDays}
          />
        </div>
      </div>
    </SectionCard>
  )
}
