/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SHOPIFY_API_KEY: string
  readonly VITE_CALENDLY_SETUP_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
