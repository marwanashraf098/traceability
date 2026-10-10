import { CSSProperties, DragEvent, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, CircleAlert, Image as ImageIcon, Upload } from 'lucide-react'
import {
  PORTAL_LOGO_MAX_BYTES, PortalSettings, PortalSettingsError, fetchPortalLogo, removePortalLogo, uploadPortalLogo,
} from '../../api'
import { Button, cn, useToast } from '../../components/ui'
import { palette } from '../../portal/brand'
import {
  PORTAL_FONTS, PortalFont, DEFAULT_PORTAL_FONT, fontLabel, fontStack, fontVars, loadPortalFont, loadPortalFontRegular,
} from '../../portal/fonts'
import portalEn from '../../portal/locales/en.json'
import portalAr from '../../portal/locales/ar.json'

/**
 * Returns portal P1 — the Branding section's new parts (design/Traced_portal_branding_dc.html):
 * the logo uploader (saves at once), the font picker and the live EN + AR preview. Colour and
 * policy stay in ReturnsPortalTab (they save with "Save changes", as before).
 */

const ACCEPTED = ['image/png', 'image/jpeg', 'image/webp']

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/**
 * The saved logo as a data: URL (fetchPortalLogo — authenticated; data: because the app's CSP
 * allows img-src data: but not blob:), refetched whenever its version changes.
 */
export function useUploadedLogoUrl(version: string | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!version) { setUrl(null); return }
    let cancelled = false
    fetchPortalLogo().then(dataUrl => {
      if (!cancelled) setUrl(dataUrl)
    }).catch(() => { if (!cancelled) setUrl(null) })   // the preview falls back to the wordmark
    return () => { cancelled = true }
  }, [version])
  return url
}

/** True once this src failed to load; resets when the src changes. */
function useImageFailed(src: string | null): [boolean, () => void] {
  const [failedSrc, setFailedSrc] = useState<string | null>(null)
  return [!!src && failedSrc === src, () => setFailedSrc(src)]
}

// ── Logo ─────────────────────────────────────────────────────────────────────

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading'; name: string; loaded: number; total: number }
  | { kind: 'error'; message: string }

export function LogoUploader({ settings, logoUrl, onChange }: {
  settings: PortalSettings
  /** Object URL of the saved logo (useUploadedLogoUrl). */
  logoUrl: string | null
  onChange: (s: PortalSettings) => void
}) {
  const { t } = useTranslation()
  const { toast } = useToast()
  const input = useRef<HTMLInputElement>(null)
  const abort = useRef<(() => void) | null>(null)
  const [state, setState] = useState<UploadState>({ kind: 'idle' })
  const [justSaved, setJustSaved] = useState(false)
  const [fileName, setFileName] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const [removing, setRemoving] = useState(false)
  const logo = settings.logo ?? null
  const [thumbFailed, onThumbError] = useImageFailed(logoUrl)

  function errorFor(code: string | null, name: string, size: number): string {
    const key = code === 'FILE_TOO_LARGE' ? 'LOGO_TOO_LARGE' : code
    return t(`settings.portal.branding.uploadErrors.${key && key in uploadErrorKeys ? key : 'generic'}`,
      { name, size: formatSize(size) })
  }

  async function upload(file: File) {
    setJustSaved(false)
    // Answered here first (the backend checks again, whatever the file is called).
    if (!ACCEPTED.includes(file.type)) {
      setState({ kind: 'error', message: errorFor('LOGO_TYPE', file.name, file.size) })
      return
    }
    if (file.size > PORTAL_LOGO_MAX_BYTES) {
      setState({ kind: 'error', message: errorFor('LOGO_TOO_LARGE', file.name, file.size) })
      return
    }
    setState({ kind: 'uploading', name: file.name, loaded: 0, total: file.size })
    const { promise, abort: cancel } = uploadPortalLogo(file, (loaded, total) =>
      setState(s => (s.kind === 'uploading' ? { ...s, loaded, total } : s)))
    abort.current = cancel
    try {
      const s = await promise
      setFileName(file.name)
      setState({ kind: 'idle' })
      setJustSaved(true)
      onChange(s)
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        setState({ kind: 'idle' })
      } else {
        setState({ kind: 'error', message: errorFor(e instanceof PortalSettingsError ? e.code : null, file.name, file.size) })
      }
    } finally {
      abort.current = null
    }
  }

  async function remove() {
    setRemoving(true)
    try {
      const s = await removePortalLogo()
      setJustSaved(false)
      setFileName(null)
      setState({ kind: 'idle' })
      onChange(s)
      toast({ tone: 'success', message: t('settings.portal.branding.logoRemoved') })
    } catch {
      toast({ tone: 'error', message: t('settings.portal.branding.removeFailed') })
    } finally {
      setRemoving(false)
    }
  }

  function pick(files: FileList | null) {
    const file = files?.[0]
    if (file) upload(file)
    if (input.current) input.current.value = ''
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    pick(e.dataTransfer.files)
  }

  const hiddenInput = (
    <input
      ref={input}
      type="file"
      accept={ACCEPTED.join(',')}
      className="sr-only"
      tabIndex={-1}
      aria-hidden="true"
      data-testid="logo-file-input"
      onChange={e => pick(e.target.files)}
    />
  )

  const error = state.kind === 'error' && (
    <p className="text-small text-critical flex items-start gap-1.5" role="alert" data-testid="logo-error">
      <CircleAlert className="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />{state.message}
    </p>
  )

  let body
  if (state.kind === 'uploading') {
    body = (
      <div className="flex items-center gap-4 rounded-xl border border-line bg-panel p-3.5" aria-busy="true" data-testid="logo-uploading">
        <div className="w-[200px] h-[72px] rounded-lg bg-elevated shrink-0 grid place-items-center">
          <span className="w-5 h-5 rounded-full border-2 border-grey-200 border-t-trace-blue animate-spin" aria-hidden="true" />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-body font-medium text-primary truncate" dir="auto">{state.name}</p>
          <p className="text-small text-muted" role="status">
            {t('settings.portal.branding.uploading', { loaded: formatSize(state.loaded), total: formatSize(state.total) })}
          </p>
          <div className="h-1.5 rounded-full bg-elevated overflow-hidden mt-2">
            <div className="h-full bg-trace-blue rounded-full transition-[width]"
              style={{ width: `${state.total ? Math.round((state.loaded / state.total) * 100) : 0}%` }} />
          </div>
        </div>
        <Button variant="secondary" size="sm" onClick={() => abort.current?.()}>{t('settings.portal.branding.cancel')}</Button>
      </div>
    )
  } else if (logo) {
    const type = logo.contentType === 'image/png' ? 'PNG' : 'JPG'
    body = (
      <div className="flex items-center gap-4 rounded-xl border border-line bg-panel p-3.5 flex-wrap" data-testid="logo-saved">
        <div className="w-[200px] h-[72px] rounded-lg border border-line bg-white shrink-0 grid place-items-center p-2.5">
          {logoUrl && !thumbFailed && <img src={logoUrl} alt={t('settings.portal.branding.logoPreview')}
            className="max-h-11 max-w-[170px] object-contain" data-testid="uploaded-logo-preview" onError={onThumbError} />}
          {logoUrl && thumbFailed && (
            <span className="w-11 h-11 rounded-lg bg-elevated text-muted grid place-items-center" aria-hidden="true"
              data-testid="logo-placeholder">
              <ImageIcon className="w-5 h-5" />
            </span>
          )}
        </div>
        <div className="flex-1 min-w-[140px]">
          <p className="text-body font-medium text-primary truncate" dir="auto">{fileName ?? t('settings.portal.branding.yourLogo')}</p>
          <p className="text-small text-muted">
            <bdi dir="ltr">{t('settings.portal.branding.logoMeta', { type, width: logo.width, height: logo.height })}</bdi>
            {justSaved && (
              <span className="inline-flex items-center gap-1 text-success-text font-semibold ms-2">
                <Check className="w-3.5 h-3.5" aria-hidden="true" />{t('settings.portal.branding.savedPill')}
              </span>
            )}
          </p>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="secondary" size="sm" onClick={() => input.current?.click()}>{t('settings.portal.branding.replace')}</Button>
          {/* Button has no red-text variant (and cn doesn't merge): same metrics as ghost sm, critical text. */}
          <button type="button" onClick={remove} disabled={removing} aria-busy={removing}
            className="btn rounded-xl transition-colors px-3 py-1.5 text-small gap-1.5 bg-transparent text-critical-text hover:bg-critical/10 disabled:opacity-60">
            {t('settings.portal.branding.remove')}
          </button>
        </div>
      </div>
    )
  } else {
    body = (
      <button
        type="button"
        onClick={() => input.current?.click()}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        aria-describedby="portal-logo-help"
        data-testid="logo-dropzone"
        className={cn(
          'w-full flex items-center gap-4 rounded-xl border-[1.5px] border-dashed p-5 text-start transition-colors',
          state.kind === 'error' ? 'border-critical bg-critical/5'
            : dragging ? 'border-trace-blue bg-trace-blue/5' : 'border-grey-100 bg-panel hover:border-trace-blue hover:bg-trace-blue/5',
        )}
      >
        <span className={cn('w-11 h-11 rounded-lg grid place-items-center shrink-0',
          state.kind === 'error' ? 'bg-panel text-critical' : 'bg-elevated text-muted')}>
          <Upload className="w-5 h-5" aria-hidden="true" />
        </span>
        <span>
          <span className="block text-body font-medium text-primary">
            {t('settings.portal.branding.dropPrompt')}{' '}
            <span className="text-trace-blue font-semibold">{t('settings.portal.branding.dropChoose')}</span>
          </span>
          <span className="block text-small text-muted" id="portal-logo-help">{t('settings.portal.branding.uploadHelp')}</span>
        </span>
      </button>
    )
  }

  return (
    <div className="space-y-2" role="group" aria-labelledby="portal-logo-title">
      <p id="portal-logo-title" className="text-body font-medium text-primary">{t('settings.portal.branding.logo')}</p>
      {hiddenInput}
      {body}
      {error}
      {justSaved && logo && (
        <p className="text-small font-semibold text-success-text flex items-center gap-1.5" role="status" data-testid="logo-saved-note">
          <Check className="w-4 h-4" aria-hidden="true" />{t('settings.portal.branding.logoSaved')}
        </p>
      )}
      {logo && <p className="text-small text-muted">{t('settings.portal.branding.uploadHelp')} {t('settings.portal.branding.savesOnUpload')}</p>}
    </div>
  )
}

const uploadErrorKeys = { LOGO_TYPE: 1, LOGO_TOO_LARGE: 1, LOGO_PIXELS: 1, LOGO_UNREADABLE: 1 }

// ── Font ─────────────────────────────────────────────────────────────────────

/** Fixed sample lines (never translated — they show the font in both scripts). */
const SAMPLE_AR = `${portalAr.p1.title} · ${portalAr.p1.find}`
const SAMPLE_EN = `${portalEn.p1.title} · ${portalEn.p1.find}`

export function FontPicker({ value, onChange }: { value: PortalFont; onChange: (f: PortalFont) => void }) {
  const { t } = useTranslation()
  // Each card is set in its own family: the regular weight of all five (settings page only).
  useEffect(() => { PORTAL_FONTS.forEach(f => { loadPortalFontRegular(f) }) }, [])
  return (
    <fieldset className="space-y-2" data-testid="font-picker">
      <legend className="text-body font-medium text-primary">{t('settings.portal.branding.fontLabel')}</legend>
      <p className="text-small text-muted -mt-1">{t('settings.portal.branding.fontHelp')}</p>
      <div className="grid gap-2.5 grid-cols-[repeat(auto-fill,minmax(210px,1fr))]">
        {PORTAL_FONTS.map(f => {
          const checked = value === f
          return (
            <label key={f}
              className={cn('relative flex flex-col gap-1.5 rounded-xl border p-3 cursor-pointer min-h-[104px] transition-colors',
                'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-trace-blue',
                checked ? 'border-trace-blue ring-1 ring-trace-blue bg-trace-blue/5' : 'border-grey-100 bg-panel hover:border-grey-300')}
              style={{ fontFamily: fontStack(f) }}
              data-font={f}
            >
              <input type="radio" name="portal-font" value={f} checked={checked} onChange={() => onChange(f)}
                className="sr-only" aria-label={fontLabel(f)} />
              <span className="flex items-center justify-between gap-2">
                <span className="text-body font-semibold text-primary">{fontLabel(f)}</span>
                {f === DEFAULT_PORTAL_FONT
                  ? <span className="font-sans text-[11px] font-medium text-muted bg-elevated rounded px-1.5 py-1">{t('settings.portal.branding.fontDefault')}</span>
                  : <span />}
                <span aria-hidden="true" className={cn('w-[18px] h-[18px] rounded-full border-[1.5px] grid place-items-center shrink-0',
                  checked ? 'border-trace-blue bg-trace-blue' : 'border-grey-100')}>
                  {checked && <span className="w-1.5 h-1.5 rounded-full bg-white" />}
                </span>
              </span>
              {/* Family set inline: the app's [dir="rtl"] rule (index.css) would otherwise force Cairo here. */}
              <span className="text-[17px] leading-relaxed text-primary whitespace-nowrap overflow-hidden" dir="rtl" lang="ar"
                style={{ fontFamily: fontStack(f) }}>{SAMPLE_AR}</span>
              <span className="text-small text-muted" dir="ltr" lang="en">{SAMPLE_EN}</span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}

// ── Preview ──────────────────────────────────────────────────────────────────

/** The portal header + "Start a return" card, EN and AR — portal copy, the chosen font and colour. */
export function PortalPreview({ font, brandColor, logoSrc, storeName }: {
  font: PortalFont
  brandColor: string | null
  /** Uploaded logo object URL, else a valid Shopify link, else null (wordmark). */
  logoSrc: string | null
  storeName: string
}) {
  const { t } = useTranslation()
  useEffect(() => { loadPortalFont(font) }, [font])
  const colors = useMemo(() => palette(brandColor), [brandColor])
  const style = {
    ...fontVars(font),
    fontFamily: fontStack(font),
    fontSynthesis: 'none',
    '--brand': colors.brand,
    '--on-brand': colors.onBrand,
    '--accent-text': colors.accentText,
  } as CSSProperties
  return (
    <aside className="flex flex-col gap-3.5" aria-label={t('settings.portal.branding.preview')} data-testid="portal-preview" data-font={font}>
      <div className="flex items-center justify-between">
        <span className="text-small font-semibold text-muted uppercase tracking-wide">{t('settings.portal.branding.preview')}</span>
        <span className="text-small text-muted">{t('settings.portal.branding.previewNote')}</span>
      </div>
      <div className="grid gap-3.5 sm:grid-cols-2 xl:grid-cols-1">
        <MiniPortal lang="en" style={style} logoSrc={logoSrc} storeName={storeName} label={t('settings.portal.branding.previewEn')} />
        <MiniPortal lang="ar" style={style} logoSrc={logoSrc} storeName={storeName} label={t('settings.portal.branding.previewAr')} />
      </div>
    </aside>
  )
}

function MiniPortal({ lang, style, logoSrc, storeName, label }: {
  lang: 'en' | 'ar'; style: CSSProperties; logoSrc: string | null; storeName: string; label: string
}) {
  const copy = lang === 'ar' ? portalAr : portalEn
  const semibold = { fontWeight: 'var(--pp-w600, 600)' } as CSSProperties
  // A logo that fails to load shows the store-name wordmark, as the portal does.
  const [failed, onError] = useImageFailed(logoSrc)
  return (
    <div role="img" aria-label={label} className="rounded-xl border border-line overflow-hidden bg-[#F7F7F5] shadow-sm"
      dir={lang === 'ar' ? 'rtl' : 'ltr'} lang={lang} style={style} data-testid={`preview-${lang}`}>
      <div className="h-12 ps-3.5 pe-2 flex items-center gap-1 bg-white border-b border-[#E6E7E3]">
        {logoSrc && !failed
          ? <img src={logoSrc} alt="" className="h-7 max-w-[140px] object-contain" data-testid={`preview-${lang}-logo`}
              onError={onError} />
          : <span className="text-[14px] font-bold tracking-[0.08em] uppercase truncate" style={{ color: 'var(--accent-text)' }}
              dir="auto" data-testid={`preview-${lang}-wordmark`}>{storeName}</span>}
        <span className="flex-1" />
        <span className="text-[13px] px-1.5" style={{ ...semibold, color: 'var(--accent-text)',
          fontFamily: lang === 'ar' ? "'Geist Variable', sans-serif" : undefined }}>{copy.common.switchTo}</span>
      </div>
      <div className="p-3.5 flex flex-col gap-2.5 text-[#141821]">
        <p className="text-[20px] font-bold leading-tight m-0">{copy.p1.title}</p>
        <p className="text-[13px] leading-normal text-[#5B6270] m-0">{copy.p1.intro}</p>
        <span className="text-[13px]" style={semibold}>{copy.p1.orderLabel}</span>
        <span className="h-[38px] rounded-lg border border-[#CDD3DC] bg-white px-3 flex items-center text-[14px] text-[#8A8F98]">
          {copy.p1.orderPlaceholder}
        </span>
        <span className="h-10 rounded-[9px] grid place-items-center text-[14px]"
          style={{ ...semibold, background: 'var(--brand)', color: 'var(--on-brand)' }}>{copy.p1.find}</span>
      </div>
    </div>
  )
}
