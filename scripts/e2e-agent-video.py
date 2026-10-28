#!/usr/bin/env python3
"""Prova ponta a ponta do vídeo publicado pelo AGENTE (câmera atrás de NAT), com o servidor de mídia e o ffmpeg reais.

Fluxo provado: fonte RTSP local (a de teste do dev-media) -> agente (VideoRelay + ffmpeg, só de dentro para fora) -> MediaMTX
(caminho cam-<id>) -> sessão HLS emitida pela API -> playlist HLS servida com token. Também prova: sem token = 401; se o ffmpeg
do agente morre, a supervisão o reabre e o vídeo volta; ao parar o agente o vídeo some.
NÃO prova câmera real nem internet real (tudo em 127.0.0.1). Requisitos: `scripts/dev-media.sh up` (MediaMTX em 8554/8888, que
valida na API em 8081, por isso a API sobe nessa porta), Postgres em 5433, jar do backend e do agente, ffmpeg.
"""
import json, os, signal, subprocess, sys, time, urllib.error, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
DB = f"e2e_agentvideo_{os.getpid()}"
LOGDIR = os.environ.get("TMPDIR", "/tmp")
PUBLISH_SECRET = os.environ.get("MEDIA_PUBLISH_SECRET", "dev-only-media-publish-secret")
failed = False
procs: list[subprocess.Popen] = []


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def psql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", "postgres", "-c", sql],
                          capture_output=True, text=True)


def api(method, path, subject, body=None):
    token = json.load(urllib.request.urlopen(f"http://localhost:8081/dev/token?subject={subject}"))["access_token"]
    req = urllib.request.Request(f"http://localhost:8081{path}", method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def status_of(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.status, r.read(2000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:
        return 0, ""


def wait_for(cond, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.5)
    return cond()


for need in ("backend/target/platform-0.1.0-SNAPSHOT.jar", "agent/target/agent-0.1.0-SNAPSHOT.jar"):
    if not os.path.exists(need):
        raise SystemExit(f"{need} não encontrado (mvn package -DskipTests no módulo)")
if status_of("http://127.0.0.1:9997/v3/config/global/get")[0] != 200:
    raise SystemExit("MediaMTX não está no ar: rode scripts/dev-media.sh up")
if status_of("http://localhost:8082/actuator/health")[0] == 200:
    raise SystemExit("já há uma API em 8081/8082: pare-a antes (o MediaMTX valida nela)")
assert psql(f"CREATE DATABASE {DB}").returncode == 0, "Postgres do compose não está no ar"
relay = None
try:
    env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
               SPRING_PROFILES_ACTIVE="dev", PORTARIA_RATE_LIMIT_API_PER_MINUTE="1000000")
    procs.append(subprocess.Popen(["java", "-jar", "backend/target/platform-0.1.0-SNAPSHOT.jar"], env=env,
                                  stdout=open(f"{LOGDIR}/e2e-agentvideo-api.log", "w"), stderr=subprocess.STDOUT, start_new_session=True))
    if not wait_for(lambda: status_of("http://localhost:8082/actuator/health")[0] == 200, 120):
        raise SystemExit("API não subiu")
    condo = api("GET", "/api/v1/condominiums", "manager-sol")["items"][0]["id"]
    cam = api("POST", f"/api/v1/condominiums/{condo}/devices", "manager-sol",
              {"name": "Camera NAT e2e", "kind": "CAMERA", "adapter": "ONVIF", "entrance": "garagem", "config": {"videoSource": "AGENT"}})
    check("a câmera publicada pelo agente é cadastrada sem nenhum endereço RTSP na central", cam["liveVideo"] and "rtspUrl" not in cam["config"])

    def session():
        s = api("POST", f"/api/v1/condominiums/{condo}/devices/{cam['id']}/stream-session", "manager-sol")
        return s["hlsUrl"]

    hls = session()
    check("a API emite a sessão HLS com token", "token=" in hls and f"cam-{cam['id']}" in hls)
    check("antes de o agente publicar não há vídeo (a central não puxa nada)", status_of(hls)[0] in (404, 0))

    renv = dict(os.environ, PORTARIA_VIDEO_CAMERAS=f"{cam['id']}=rtsp://127.0.0.1:8554/src-portaria",
                PORTARIA_VIDEO_PUBLISH_URL="rtsp://127.0.0.1:8554", PORTARIA_VIDEO_PUBLISH_SECRET=PUBLISH_SECRET)
    relay = subprocess.Popen(["java", "-cp", "agent/target/agent-0.1.0-SNAPSHOT.jar", "br.com.domus.agent.video.VideoRelayCli"], env=renv,
                             stdout=open(f"{LOGDIR}/e2e-agentvideo-relay.log", "w"), stderr=subprocess.STDOUT, start_new_session=True)
    ok = wait_for(lambda: status_of(session())[0] == 200, 60)
    code, body = status_of(session())
    check("com o agente publicando, a playlist HLS responde 200", ok and code == 200, f"(HTTP {code})")
    check("e é uma playlist HLS de verdade", body.startswith("#EXTM3U"))
    check("sem token a mesma playlist é recusada", status_of(hls.split("?")[0])[0] == 401)

    print("== supervisão: o ffmpeg do agente morre")
    subprocess.run(["pkill", "-9", "-f", f"cam-{cam['id']}"])
    time.sleep(1)
    back = wait_for(lambda: status_of(session())[0] == 200, 60)
    check("o agente reabre o ffmpeg sozinho e o vídeo volta", back)

    print("== parar o agente")
    os.killpg(relay.pid, signal.SIGTERM)
    relay.wait(timeout=15)
    relay = None
    time.sleep(1)
    gone = wait_for(lambda: status_of(session())[0] in (404, 0), 30)
    check("com o agente parado o vídeo some (a central não inventa imagem)", gone)
    check("o log do agente não tem a senha de publicação", PUBLISH_SECRET not in open(f"{LOGDIR}/e2e-agentvideo-relay.log").read())
finally:
    if relay is not None:
        try:
            os.killpg(relay.pid, signal.SIGKILL)
        except Exception:
            pass
    for p in procs:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except Exception:
            pass
    time.sleep(1)
    psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
