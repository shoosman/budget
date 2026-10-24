/* Service worker: офлайн-кэш приложения.
   ВАЖНО: при каждой загрузке новой версии index.html на GitHub меняйте номер VERSION —
   иначе телефон и компьютер будут показывать старую версию из кэша. */
const VERSION = 'schitalka-bonus-v3';
const APP_FILES = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './icon-maskable-512.png',
  './apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION).then((cache) => cache.addAll(APP_FILES))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('message', (event) => {
  if (event.data === 'skipWaiting') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  /* GitHub API, шрифты и всё внешнее — напрямую в сеть, не кэшируем */
  if (url.origin !== self.location.origin) return;
  /* файлы приложения: сначала кэш, в фоне — сеть (обновление подхватится при следующем открытии) */
  event.respondWith(
    caches.match(event.request).then((cached) => {
      const network = fetch(event.request).then((resp) => {
        if (resp && resp.ok) caches.open(VERSION).then((c) => c.put(event.request, resp.clone()));
        return resp;
      }).catch(() => cached);
      return cached || network;
    })
  );
});
