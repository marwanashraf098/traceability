import { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { MAX_PHOTOS_PER_LINE, PHOTO_ACCEPT, PhotoErrorCode } from './photos'

export interface PhotoTile {
  key: string
  /** The phone's own JPEG as a data: URL (the returns host allows img-src data:). */
  dataUrl: string
  status: 'uploading' | 'done' | 'failed'
  progress: number
  photoId?: string
  /** Kept only for Retry. */
  blob?: Blob
}

export interface LinePhotoError { code: PhotoErrorCode | 'tooMany'; name?: string; size?: string; count?: number }

/**
 * Returns portal P3 — the photo tiles inside one selected line (design/Traced_portal_photos_dc.html,
 * b): thumbnails with remove, upload progress, failed tile with Retry, the Add tile (hidden at 3),
 * the line's own error under it.
 */
export default function LinePhotos({ lineId, title, quantity, required, tiles, error, onPick, onRemove, onRetry }: {
  lineId: string
  title: string
  quantity: number
  required: boolean
  tiles: PhotoTile[]
  error: LinePhotoError | null
  onPick: (files: File[]) => void
  onRemove: (key: string) => void
  onRetry: (key: string) => void
}) {
  const { t } = useTranslation()
  const input = useRef<HTMLInputElement>(null)
  const full = tiles.length >= MAX_PHOTOS_PER_LINE
  const hasDone = tiles.some(p => p.status === 'done')
  const status = tiles.length > 0 ? t('ph.countOf', { count: tiles.length, max: MAX_PHOTOS_PER_LINE })
    : required ? t('ph.required') : t('ph.optional', { max: MAX_PHOTOS_PER_LINE })
  const hintId = `ph-hint-${lineId}`
  return (
    <div className="pp-photos" role="group" aria-label={t('ph.groupLabel', { item: title })} data-testid="line-photos">
      <div className="pp-photos__label">
        <span>{t('ph.label')}</span>
        <small>{status}</small>
      </div>
      <div className="pp-photos__row">
        {tiles.map((p, i) => (
          <div key={p.key} className={'pp-photo' + (p.status === 'failed' ? ' pp-photo--failed' : '')}
            data-testid="photo-tile" data-status={p.status}>
            <img src={p.dataUrl} alt={t('ph.photoAlt', { n: i + 1, item: title })} />
            {p.status === 'uploading' && (
              <>
                <span className="pp-photo__veil" aria-hidden="true" />
                <span className="pp-photo__progress" role="progressbar" aria-valuemin={0} aria-valuemax={100}
                  aria-valuenow={Math.round(p.progress * 100)} aria-label={t('ph.uploading')}>
                  <i style={{ width: `${Math.round(p.progress * 100)}%` }} />
                </span>
              </>
            )}
            {p.status === 'failed' && (
              <>
                <span className="pp-photo__veil" aria-hidden="true" />
                <button type="button" className="pp-photo__retry" onClick={() => onRetry(p.key)}>↻ {t('ph.retry')}</button>
              </>
            )}
            {p.status !== 'uploading' && (
              <button type="button" className="pp-photo__remove" aria-label={t('ph.remove', { n: i + 1 })}
                onClick={() => onRemove(p.key)}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" />
                </svg>
              </button>
            )}
          </div>
        ))}
        {!full && (
          <button type="button" className={'pp-photo-add' + (required && !hasDone && tiles.length === 0 ? ' pp-photo-add--need' : '')}
            onClick={() => input.current?.click()} aria-describedby={hintId}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
              <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
            </svg>
            {tiles.length === 0 ? t('ph.addPhoto') : t('ph.add')}
          </button>
        )}
        <input ref={input} type="file" accept={PHOTO_ACCEPT} multiple className="pp-visually-hidden" tabIndex={-1}
          aria-hidden="true" data-testid="photo-input"
          onChange={e => { onPick(Array.from(e.target.files ?? [])); e.target.value = '' }} />
      </div>
      {error
        ? <p className="pp-fielderror" role="alert">{t(`ph.errors.${error.code}`, { name: error.name, size: error.size, count: error.count })}</p>
        : <p className="pp-hint" id={hintId}>
            {full ? t('ph.maxReached', { max: MAX_PHOTOS_PER_LINE })
              : required && tiles.length === 0 ? (quantity > 1 ? t('ph.needOneQty', { count: quantity }) : t('ph.needOne'))
                : t('ph.hint')}
          </p>}
    </div>
  )
}
