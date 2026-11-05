/** S1b: a Return To Receive alert's link into Receiving carries the return it brings back (?return=<key>). */
export function withReturnSource(url: string, returnSource?: string | null): string {
  if (!returnSource) return url
  return `${url}${url.includes('?') ? '&' : '?'}return=${encodeURIComponent(returnSource)}`
}
