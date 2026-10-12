/* هم‌صدا offline copy: every file is kept on the device after the first visit */
const CACHE = 'hamseda-3ee30fce64';
const FILES = ["./", "./index.html", "./builder.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./maskable-512.png", "./apple-touch-icon.png"];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES.map(f => new Request(f, {cache: 'reload'})))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k.startsWith('hamseda-') && k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if(req.method !== 'GET' || new URL(req.url).origin !== self.location.origin) return;
  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req, {ignoreSearch: true});
    if(hit) return hit;
    try { return await fetch(req); }
    catch(err){
      if(req.mode === 'navigate'){ const home = await cache.match('./index.html'); if(home) return home; }
      throw err;
    }
  })());
});
