/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SHOPIFY_API_KEY: string
  readonly VITE_CALENDLY_SETUP_URL: string
  /** "true" shows the Analytics nav group and routes (default off). */
  readonly VITE_ANALYTICS_ENABLED?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
