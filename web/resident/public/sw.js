// Service worker mínimo: guarda apenas o "casco" estático para abrir o app instalado sem rede.
// Respostas da API NUNCA são armazenadas em cache (dados pessoais e credenciais).
// Mude VERSION quando alterar este arquivo: ao ativar, todo cache de versão anterior é apagado.
const VERSION = "v2";
const SHELL = "shell-" + VERSION;

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(["/", "/icon.svg"])).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((names) => Promise.all(names.filter((n) => n !== SHELL).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.pathname.startsWith("/api") || url.pathname.startsWith("/dev")
      || url.pathname === "/config.json") return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        // a página inicial online renova o casco guardado: sem isso, o index.html offline apontaria para arquivos de versões antigas
        if (res.ok && e.request.mode === "navigate") {
          const copy = res.clone();
          caches.open(SHELL).then((c) => c.put("/", copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((r) => r ?? caches.match("/")))
  );
});
