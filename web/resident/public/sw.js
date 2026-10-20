// Service worker mínimo: guarda apenas o "casco" estático para abrir o app instalado.
// Respostas da API NUNCA são armazenadas em cache (dados pessoais e credenciais).
const SHELL = "shell-v1";
self.addEventListener("install", (e) => e.waitUntil(caches.open(SHELL).then((c) => c.addAll(["/", "/icon.svg"]))));
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.pathname.startsWith("/api") || url.pathname.startsWith("/dev")) return;
  e.respondWith(fetch(e.request).catch(() => caches.match(e.request).then((r) => r ?? caches.match("/"))));
});
