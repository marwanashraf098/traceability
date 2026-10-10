/**
 * Returns portal P3 — photos of the items being returned (design/Traced_portal_photos_dc.html, b).
 *
 * preparePhoto: decodes the picked file in the browser (createImageBitmap — no object URL, the
 * returns host's CSP has no blob:), re-encodes it as JPEG with the long edge ≤ 2400 px (HEIC/HEIF
 * included, when the browser can decode them) and returns it with a data: URL for the thumbnail
 * (img-src data: is allowed on the returns host). The server re-checks and re-encodes everything.
 */

export const MAX_PHOTOS_PER_LINE = 3
export const MAX_PHOTO_BYTES = 8 * 1024 * 1024
const CLIENT_MAX_EDGE = 2400
export const PHOTO_ACCEPT = 'image/jpeg,image/png,image/webp,image/heic,image/heif,.heic,.heif'

export type PhotoErrorCode = 'type' | 'tooBig' | 'pixels' | 'failed' | 'unreadable'

export class PhotoPrepError extends Error {
  constructor(public readonly code: PhotoErrorCode) { super(code) }
}

function looksLikePhoto(file: File): boolean {
  const name = file.name.toLowerCase()
  return /^image\/(jpeg|png|webp|heic|heif)$/.test(file.type) || /\.(jpe?g|png|webp|heic|heif)$/.test(name)
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(r.error ?? new Error('read failed'))
    r.readAsDataURL(blob)
  })
}

export interface PreparedPhoto { blob: Blob; dataUrl: string }

export async function preparePhoto(file: File): Promise<PreparedPhoto> {
  if (!looksLikePhoto(file)) throw new PhotoPrepError('type')
  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    // e.g. HEIC on a browser that can't decode it — the customer can pick a JPEG instead.
    throw new PhotoPrepError('type')
  }
  const scale = Math.min(1, CLIENT_MAX_EDGE / Math.max(bitmap.width, bitmap.height))
  const w = Math.max(1, Math.round(bitmap.width * scale)), h = Math.max(1, Math.round(bitmap.height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new PhotoPrepError('unreadable')
  ctx.fillStyle = '#FFFFFF'
  ctx.fillRect(0, 0, w, h)
  ctx.drawImage(bitmap, 0, 0, w, h)
  bitmap.close?.()
  const blob = await new Promise<Blob | null>(res => canvas.toBlob(res, 'image/jpeg', 0.85))
  if (!blob) throw new PhotoPrepError('unreadable')
  if (blob.size > MAX_PHOTO_BYTES) throw new PhotoPrepError('tooBig')
  return { blob, dataUrl: await blobToDataUrl(blob) }
}

export interface UploadedPhoto { photoId: string; width: number; height: number }

/** POST /api/v1/portal/{slug}/photos — XHR for upload progress. Rejects with PhotoPrepError. */
export function uploadPhoto(slug: string, token: string, blob: Blob, onProgress?: (fraction: number) => void) {
  const xhr = new XMLHttpRequest()
  const promise = new Promise<UploadedPhoto>((resolve, reject) => {
    xhr.open('POST', `/api/v1/portal/${encodeURIComponent(slug)}/photos`)
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.upload.onprogress = e => { if (e.lengthComputable) onProgress?.(e.loaded / e.total) }
    xhr.onload = () => {
      let body: { photoId?: string; width?: number; height?: number; error?: string } | null = null
      try { body = JSON.parse(xhr.responseText) } catch { body = null }
      if (xhr.status === 201 && body?.photoId) {
        resolve({ photoId: body.photoId, width: body.width ?? 0, height: body.height ?? 0 })
      } else if (xhr.status === 400) {
        reject(new PhotoPrepError(body?.error === 'PHOTO_TYPE' ? 'type' : body?.error === 'PHOTO_PIXELS' ? 'pixels'
          : body?.error === 'PHOTO_TOO_LARGE' ? 'tooBig' : 'unreadable'))
      } else if (xhr.status === 413) {
        reject(new PhotoPrepError('tooBig'))
      } else {
        reject(new PhotoPrepError('failed'))
      }
    }
    xhr.onerror = () => reject(new PhotoPrepError('failed'))
    const form = new FormData()
    form.append('file', blob, 'photo.jpg')
    xhr.send(form)
  })
  return { promise, abort: () => xhr.abort() }
}
