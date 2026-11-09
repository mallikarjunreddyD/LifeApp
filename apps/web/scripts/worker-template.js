const CACHE = /* VERSION */ '';
const SHELL = new Set(/* SHELL */ []);
const scope = self;

scope.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache =>
    cache.addAll([...SHELL].map(path => new Request(path, { cache: 'reload', credentials: 'omit' })))));
});

scope.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith('lifeapp-shell-') && key !== CACHE).map(key => caches.delete(key)),
  )).then(() => scope.clients.claim()));
});

scope.addEventListener('message', event => {
  if (event.data === 'LIFEAPP_ACTIVATE') event.waitUntil(scope.skipWaiting());
});

scope.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== scope.location.origin || url.search) return;
  const path = event.request.mode === 'navigate' && url.pathname === '/' ? '/index.html' : url.pathname;
  if (!SHELL.has(path)) return;
  event.respondWith(caches.open(CACHE).then(async cache => {
    const saved = await cache.match(path);
    return saved ?? fetch(event.request);
  }));
});
