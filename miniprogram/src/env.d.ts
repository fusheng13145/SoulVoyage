/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 小程序线上接口域名（HTTPS + 备案），例如 https://api.example.com */
  readonly VITE_ONLINE_ORIGIN?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
