#!/usr/bin/env python3
"""Prova que o CSP de produção (deploy/caddy/Caddyfile) deixa o vídeo ao vivo funcionar (navegador real, Caddy real, MediaMTX real).

O e2e-browser.py roda no Vite, sem o CSP nem o proxy de produção, então não pega um bloqueio do navegador. Aqui:
  1. sobe as fontes de vídeo falsas (scripts/dev-media.sh) e um Caddy real com o Caddyfile de produção, o host de vídeo ligado
     (video.caddy) e uma página de sonda no lugar do painel (o CSP vem do Caddyfile, não da página);
  2. a sonda carrega o hls.js e toca uma câmera em https://video.localhost, como o painel faz;
  3. o vídeo precisa andar (currentTime) e não pode haver violação de CSP;
  4. controle negativo: com o CSP ANTIGO (sem video/blob) o mesmo teste tem de falhar, senão a prova não prova nada.
Requisitos: Docker (Postgres do compose no ar), ffmpeg, Playwright (chromium), imagens caddy:2 e bluenviron/mediamtx:1.15.1, jar do backend
empacotado. Sobe a própria API de desenvolvimento num banco temporário (o MediaMTX valida publicação no backend). Portas locais 8443 e 8081.
"""
import os, re, shutil, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 8443
NAME = "domus-csp-caddy"
OLD_CSP = ("default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
           "connect-src 'self' https://auth.{$DOMAIN}{$PUBLIC_PORT_SUFFIX}; frame-ancestors 'none'; base-uri 'none'")
failed = False
DB = "csp_video_probe"
api_proc = None


def psql(sql):
    return sh("docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", "postgres", "-c", sql, cwd=ROOT)


def start_api():
    global api_proc
    psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")
    if psql(f"CREATE DATABASE {DB}").returncode != 0:
        raise SystemExit("Postgres do compose não está no ar")
    env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
               SPRING_PROFILES_ACTIVE="dev", PORTARIA_RATE_LIMIT_ENABLED="false")
    api_proc = subprocess.Popen(["java", "-jar", os.path.join(ROOT, "backend/target/platform-0.1.0-SNAPSHOT.jar")], env=env,
                                stdout=open(os.path.join(tempfile.gettempdir(), "csp-video-api.log"), "w"), stderr=subprocess.STDOUT)
    for _ in range(90):
        if sh("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8082/actuator/health/readiness").stdout == "200":
            return
        time.sleep(1)
    raise SystemExit("a API não subiu (veja csp-video-api.log em /tmp)")


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def sh(*a, **k):
    return subprocess.run(a, capture_output=True, text=True, **k)


def run_probe(caddyfile_text, tmp, label):
    """Sobe o Caddy com este Caddyfile, abre a sonda no Chromium e devolve (tocou, violações de CSP, detalhe)."""
    from playwright.sync_api import sync_playwright
    cf = os.path.join(tmp, f"Caddyfile-{label}")
    open(cf, "w").write(caddyfile_text)
    sh("docker", "rm", "-f", NAME)
    r = sh("docker", "run", "-d", "--name", NAME, "--add-host", "host.docker.internal:host-gateway", "-p", f"127.0.0.1:{PORT}:443",
           "-e", "DOMAIN=localhost", "-e", f"PUBLIC_PORT_SUFFIX=:{PORT}", "-e", "MEDIA_UPSTREAM=host.docker.internal:8888",
           "-v", f"{cf}:/etc/caddy/Caddyfile:ro", "-v", f"{tmp}/video:/etc/caddy/video:ro", "-v", f"{tmp}/probe:/srv/panel:ro", "caddy:2")
    if r.returncode != 0:
        raise SystemExit("não subiu o Caddy: " + r.stderr)
    time.sleep(4)
    violations, state = [], {}
    with sync_playwright() as p:
        browser = p.chromium.launch(args=["--host-resolver-rules=MAP *.localhost 127.0.0.1", "--autoplay-policy=no-user-gesture-required"])
        page = browser.new_context(ignore_https_errors=True).new_page()
        page.on("console", lambda m: violations.append(m.text) if "Content Security Policy" in m.text or "Refused to" in m.text else None)
        page.goto(f"https://panel.localhost:{PORT}/index.html?src=src-portaria", wait_until="load")
        deadline = time.time() + 30
        while time.time() < deadline:
            state = page.evaluate("() => window.__probe || {}")
            if state.get("time", 0) > 1.5 or state.get("error"):
                break
            time.sleep(1)
        browser.close()
    sh("docker", "rm", "-f", NAME)
    return state.get("time", 0) > 1.5, violations, state


def main():
    print("subindo a API de desenvolvimento e as fontes de vídeo falsas...")
    start_api()
    subprocess.run([os.path.join(ROOT, "scripts/dev-media.sh"), "up"], check=True)
    for _ in range(40):  # as fontes só publicam depois que a API valida a publicação
        if sh("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8888/src-portaria/index.m3u8").stdout == "200":
            break
        time.sleep(1)
    tmp = tempfile.mkdtemp(prefix="csp-video-")
    try:
        os.makedirs(f"{tmp}/probe"); os.makedirs(f"{tmp}/video")
        shutil.copy(os.path.join(ROOT, "web/node_modules/hls.js/dist/hls.min.js"), f"{tmp}/probe/hls.min.js")
        shutil.copy(os.path.join(ROOT, "deploy/caddy/video/video.caddy.example"), f"{tmp}/video/video.caddy")
        open(f"{tmp}/probe/index.html", "w").write(
            '<!doctype html><meta charset="utf-8"><title>sonda</title><video id="v" muted playsinline></video>'
            '<script src="/hls.min.js"></script><script src="/probe.js"></script>')
        open(f"{tmp}/probe/probe.js", "w").write("""
const src = new URLSearchParams(location.search).get('src');
window.__probe = {};
const v = document.getElementById('v');
const hls = new Hls();
hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) window.__probe = { ...window.__probe, error: d.details }; });
hls.loadSource('https://video.localhost:%d/' + src + '/index.m3u8');
hls.attachMedia(v);
v.addEventListener('timeupdate', () => { window.__probe = { ...window.__probe, time: v.currentTime }; });
v.play().catch(() => {});
""" % PORT)
        prod = open(os.path.join(ROOT, "deploy/caddy/Caddyfile")).read()
        print("== Caddyfile de produção (CSP atual)")
        ok, viol, st = run_probe(prod, tmp, "new")
        check("o vídeo toca pelo host de vídeo sob o CSP de produção", ok, str(st))
        check("nenhuma violação de CSP", not viol, str(viol[:2]))
        print("== controle negativo (CSP antigo, sem host de vídeo nem blob:)")
        new_csp = re.search(r'header Content-Security-Policy "([^"]+)"', prod).group(1)
        ok_old, viol_old, st_old = run_probe(prod.replace(new_csp, OLD_CSP), tmp, "old")
        check("com o CSP antigo o vídeo NÃO toca (a prova pega o defeito)", not ok_old, str(st_old))
        check("e o navegador acusa violação de CSP", bool(viol_old), str(viol_old[:1]))
    finally:
        sh("docker", "rm", "-f", NAME)
        subprocess.run([os.path.join(ROOT, "scripts/dev-media.sh"), "down"], capture_output=True)
        if api_proc:
            api_proc.terminate()
            api_proc.wait(timeout=30)
        psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")
        shutil.rmtree(tmp, ignore_errors=True)
    print("RESULTADO:", "FALHOU" if failed else "OK")
    sys.exit(1 if failed else 0)


main()
