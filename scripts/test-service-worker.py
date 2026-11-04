#!/usr/bin/env python3
"""Prova o service worker do app do morador num Chromium real (build de produção servido localmente):
cache de versão antiga é apagado ao ativar, o casco novo é guardado e o app abre sem rede. Requer `npm run build` em web/ e Playwright."""
import functools, http.server, os, sys, threading
from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "web/resident/dist")
failed = False


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=DIST))
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f"http://localhost:{server.server_address[1]}"

with sync_playwright() as p:
    browser = p.chromium.launch()
    ctx = browser.new_context()
    page = ctx.new_page()
    page.goto(f"{base}/icon.svg")  # mesma origem, ainda sem service worker
    page.evaluate("""async () => { const c = await caches.open('shell-v1'); await c.put('/', new Response('casco antigo')); }""")
    page.goto(f"{base}/")
    page.evaluate("() => navigator.serviceWorker.ready")
    page.reload()  # agora controlado pelo service worker
    page.wait_for_function("() => navigator.serviceWorker.controller !== null", timeout=10000)
    names = page.evaluate("() => caches.keys()")
    check("o cache da versão antiga foi apagado", "shell-v1" not in names, str(names))
    check("o casco da versão atual está guardado", "shell-v2" in names, str(names))
    page.reload()
    page.wait_for_timeout(500)
    cached = page.evaluate("async () => { const r = await (await caches.open('shell-v2')).match('/'); return r ? (await r.text()).slice(0, 15) : null; }")
    check("a página inicial guardada é a atual (não o casco antigo)", cached is not None and cached.lower().startswith("<!doctype"), str(cached))
    ctx.set_offline(True)
    page.reload()
    check("sem rede o app abre a partir do cache", page.title() != "" and page.locator("#root").count() == 1, page.title())
    browser.close()
server.shutdown()
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
