/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SHOPIFY_API_KEY: string
  readonly VITE_CALENDLY_SETUP_URL: string
  /** "true" shows the Analytics nav group and routes (default off). */
  readonly VITE_ANALYTICS_ENABLED?: string
  /** "true" turns on layout-independent scan key capture on every scan screen (default off;
   *  always on at /scan-probe). */
  readonly VITE_SCAN_KEY_CAPTURE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
