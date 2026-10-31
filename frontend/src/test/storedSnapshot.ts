import { readFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Places rollout — a snapshot another test already stored (`__snapshots__/<file>.snap`), read back as the HTML string
 * it holds, so a new test can assert "renders exactly as that stored snapshot" (e.g. places on but nothing set up ===
 * the S3–S5 off-snapshots, which are main's original screens). Throws when the key isn't there.
 */
export function storedSnapshot(file: string, key: string): string {
  const src = readFileSync(join(process.cwd(), 'src/test/__snapshots__', `${file}.snap`), 'utf8')
  const stored: Record<string, string> = {}
  new Function('exports', src)(stored)
  const value = stored[key]?.trim()
  if (value === undefined) throw new Error(`no stored snapshot "${key}" in ${file}.snap`)
  // A string snapshot is stored with its quotes, multi-line ones between newlines ("<div…>").
  return value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value
}
