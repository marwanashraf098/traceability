import { KeyboardEvent, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ChevronLeft, ChevronRight, ImageOff, Trash2, X } from 'lucide-react'
import { fetchRequestPhoto, ReturnRequestItem } from '../../api'

/**
 * Returns portal P3 — the customer's photos of one request item (design/Traced_portal_photos_dc.html,
 * c): a strip of thumbnails; a tap opens the lightbox (previous / next, arrow keys, Esc, focus kept
 * inside). Images are fetched with the signed-in session and shown as data: URLs (the app's CSP
 * allows img-src data:, not blob:). Owner / manager only — the drawer is never shown to workers.
 */
export default function ItemPhotos({ requestId, item }: { requestId: string; item: ReturnRequestItem }) {
  const { t, i18n } = useTranslation()
  const photos = item.photos ?? []
  const [urls, setUrls] = useState<Record<string, string | null>>({})
  const [open, setOpen] = useState<number | null>(null)
  const ids = photos.map(p => p.id).join(',')

  useEffect(() => {
    let cancelled = false
    for (const p of photos) {
      fetchRequestPhoto(requestId, p.id)
        .then(u => { if (!cancelled) setUrls(m => ({ ...m, [p.id]: u })) })
        .catch(() => { if (!cancelled) setUrls(m => ({ ...m, [p.id]: null })) })
    }
    return () => { cancelled = true }
    // ids is the dependency: the same photos → no refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestId, ids])

  if (photos.length === 0) {
    if (!item.photosRemoved) return null
    return (
      <p className="w-full text-small text-muted flex items-center gap-2 rounded-lg bg-elevated px-2.5 py-2" data-testid="photos-removed">
        <Trash2 className="w-4 h-4 shrink-0" aria-hidden="true" />
        {item.photosRemoved.reason === 'privacy'
          ? t('exchangesRefunds.requests.photos.removedPrivacy')
          : t('exchangesRefunds.requests.photos.removed', {
              date: new Date(item.photosRemoved.at).toLocaleDateString(i18n.language, { day: 'numeric', month: 'short', year: 'numeric' }) })}
      </p>
    )
  }

  return (
    <div className="w-full flex gap-2" data-testid="item-photos">
      {photos.map((p, i) => (
        <button key={p.id} type="button" onClick={() => setOpen(i)}
          className="w-16 h-16 rounded-lg border border-line overflow-hidden bg-elevated grid place-items-center cursor-zoom-in"
          aria-label={t('exchangesRefunds.requests.photos.open', { n: i + 1, count: photos.length, item: item.productTitle })}>
          {urls[p.id]
            ? <img src={urls[p.id]!} alt="" className="w-full h-full object-cover" data-testid="item-photo-thumb" />
            : urls[p.id] === null
              ? <ImageOff className="w-5 h-5 text-muted" aria-hidden="true" />
              : <span className="w-4 h-4 rounded-full border-2 border-grey-200 border-t-trace-blue animate-spin" aria-hidden="true" />}
        </button>
      ))}
      {open !== null && (
        <Lightbox title={item.productTitle} urls={photos.map(p => urls[p.id] ?? null)} index={open}
          onIndex={setOpen} onClose={() => setOpen(null)} />
      )}
    </div>
  )
}

function Lightbox({ title, urls, index, onIndex, onClose }: {
  title: string
  urls: Array<string | null>
  index: number
  onIndex: (i: number) => void
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const closeRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)
  const rtl = i18n.dir() === 'rtl'
  const prev = () => onIndex((index - 1 + urls.length) % urls.length)
  const next = () => onIndex((index + 1) % urls.length)

  useEffect(() => { closeRef.current?.focus() }, [])

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') { e.preventDefault(); onClose() }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); if (rtl) next(); else prev() }
    else if (e.key === 'ArrowRight') { e.preventDefault(); if (rtl) prev(); else next() }
    else if (e.key === 'Tab') {
      // Keep focus inside the dialog.
      const f = dialogRef.current?.querySelectorAll<HTMLElement>('button')
      if (!f || f.length === 0) return
      const first = f[0], last = f[f.length - 1]
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
    }
  }

  const label = t('exchangesRefunds.requests.photos.position', { item: title, n: index + 1, count: urls.length })
  return (
    <div ref={dialogRef} role="dialog" aria-modal="true" aria-label={label} onKeyDown={onKeyDown}
      className="fixed inset-0 z-[60] bg-grey-900/90 flex items-center justify-center p-4" data-testid="photo-lightbox"
      onClick={e => { if (e.target === e.currentTarget) onClose() }}>
      <div className="absolute top-3 inset-x-4 flex items-center justify-between text-small text-grey-100">
        <span data-testid="lightbox-position">{label}</span>
        <button ref={closeRef} type="button" onClick={onClose} aria-label={t('exchangesRefunds.requests.photos.close')}
          className="w-11 h-11 rounded-full bg-white/10 hover:bg-white/20 grid place-items-center text-white">
          <X className="w-5 h-5" aria-hidden="true" />
        </button>
      </div>
      {urls.length > 1 && (
        <button type="button" onClick={prev} aria-label={t('exchangesRefunds.requests.photos.previous')}
          className="absolute start-4 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full bg-white/10 hover:bg-white/20 grid place-items-center text-white">
          {rtl ? <ChevronRight className="w-6 h-6" aria-hidden="true" /> : <ChevronLeft className="w-6 h-6" aria-hidden="true" />}
        </button>
      )}
      {urls[index]
        ? <img src={urls[index]!} alt={label} className="max-h-[80vh] max-w-[min(90vw,900px)] rounded-xl object-contain" data-testid="lightbox-image" />
        : <ImageOff className="w-10 h-10 text-grey-200" aria-label={t('exchangesRefunds.requests.photos.unavailable')} />}
      {urls.length > 1 && (
        <button type="button" onClick={next} aria-label={t('exchangesRefunds.requests.photos.next')}
          className="absolute end-4 top-1/2 -translate-y-1/2 w-11 h-11 rounded-full bg-white/10 hover:bg-white/20 grid place-items-center text-white">
          {rtl ? <ChevronLeft className="w-6 h-6" aria-hidden="true" /> : <ChevronRight className="w-6 h-6" aria-hidden="true" />}
        </button>
      )}
    </div>
  )
}
