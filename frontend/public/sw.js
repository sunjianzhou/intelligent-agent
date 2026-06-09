/**
 * Service Worker for Intelligent Agent PWA
 *
 * Strategy:
 *   - JS/CSS (Vite hash filenames)  → cache-first  (hash guarantees freshness)
 *   - HTML / manifest               → network-first (always get latest shell)
 *   - API / WebSocket               → bypass        (never cache dynamic data)
 *   - Everything else               → stale-while-revalidate
 */

const CACHE   = 'ia-pwa-v2'
const OFFLINE = '/offline.html'

// ── Install: cache app shell ──────────────────────────────────────────────────
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) =>
      cache.addAll(['/', '/manifest.json'])
        .catch(() => {})      // non-critical if offline during install
    )
  )
  self.skipWaiting()
})

// ── Activate: drop stale caches ───────────────────────────────────────────────
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    )
  )
  self.clients.claim()
})

// ── Fetch ─────────────────────────────────────────────────────────────────────
self.addEventListener('fetch', (event) => {
  const { request } = event
  const url = new URL(request.url)

  // Skip non-GET, cross-origin, API, and WebSocket
  if (
    request.method !== 'GET' ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith('/api') ||
    url.pathname.startsWith('/ws')
  ) {
    return
  }

  // Vite hashed assets (contain 8-hex hash): cache-first
  if (/\.[0-9a-f]{8}\.(js|css)$/.test(url.pathname)) {
    event.respondWith(cacheFirst(request))
    return
  }

  // HTML navigation: network-first
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request))
    return
  }

  // Icons / images / fonts: stale-while-revalidate
  event.respondWith(staleWhileRevalidate(request))
})

// ── Strategies ────────────────────────────────────────────────────────────────

async function cacheFirst(req) {
  const cached = await caches.match(req)
  if (cached) return cached
  const res = await fetch(req)
  if (res.ok) {
    const cache = await caches.open(CACHE)
    cache.put(req, res.clone())
  }
  return res
}

async function networkFirst(req) {
  try {
    const res = await fetch(req)
    if (res.ok) {
      const cache = await caches.open(CACHE)
      cache.put(req, res.clone())
    }
    return res
  } catch {
    const cached = await caches.match(req)
    return cached || caches.match('/')
  }
}

async function staleWhileRevalidate(req) {
  const cache  = await caches.open(CACHE)
  const cached = await cache.match(req)
  const fetchPromise = fetch(req).then((res) => {
    if (res.ok) cache.put(req, res.clone())
    return res
  }).catch(() => null)
  return cached || fetchPromise
}
