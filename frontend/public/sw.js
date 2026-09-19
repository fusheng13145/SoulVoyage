/* 静态资源缓存：/api/** 与非 GET 一律直通，敏感数据绝不落缓存（DS4-PWA） */
const CACHE = 'sv-static-v2'
const STATIC = ['/icons.svg', '/manifest.webmanifest', '/icons/island.svg']

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(STATIC)).then(() => self.skipWaiting()))
})

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url)
  if (e.request.method !== 'GET' || url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          const c = res.clone()
          caches.open(CACHE).then((cs) => cs.put('/index-offline.html', c))
          return res
        })
        .catch(() => caches.match('/index-offline.html').then((r) => r || Response.error())),
    )
    return
  }

  e.respondWith(
    caches.match(e.request).then(
      (hit) =>
        hit ||
        fetch(e.request).then((res) => {
          if (res.ok && (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/'))) {
            caches.open(CACHE).then((cs) => cs.put(e.request, res.clone()))
          }
          return res
        }),
    ),
  )
})
