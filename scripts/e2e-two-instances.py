#!/usr/bin/env python3
"""Duas instâncias REAIS da API (JVMs separadas) no mesmo banco: só uma mantém a conexão de eventos da controladora (B7).

Equipamento Dahua FALSO (stdlib): exige Digest, mantém o fluxo `eventManager.cgi?action=attach` aberto e conta as conexões
abertas ao mesmo tempo. Provas:
1. com duas instâncias no ar, nunca há mais de 1 conexão aberta, e há exatamente 1;
2. derrubar uma instância à força (SIGKILL) não deixa a controladora sem assinatura nem com duas;
3. derrubar a segunda também zera a conexão; uma instância nova assume depois que a posse (lease) vence.
O prazo da posse e o ciclo de reconciliação são reduzidos por variável de ambiente (10 s e 3 s) para o teste não demorar.
Requisitos: Postgres em 5433 (docker compose up -d postgres) e o jar do backend. Perfil dev (dados sintéticos).
"""
import json, os, signal, subprocess, sys, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
DB = f"e2e_two_{os.getpid()}"
LOGDIR = os.environ.get("TMPDIR", "/tmp")
FAKE_PORT = 18099
failed = False
procs: list[subprocess.Popen] = []


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


# ---- equipamento Dahua falso -------------------------------------------------------------------------------------------
open_streams = 0
max_open = 0
total_connections = 0
lock = threading.Lock()
stop_fake = threading.Event()


class Fake(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        global open_streams, max_open, total_connections
        if "Authorization" not in self.headers:
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Digest realm="Login to DEVICE", qop="auth", nonce="abc123nonce"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if "eventManager.cgi" not in self.path:
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=myboundary")
        self.send_header("Connection", "close")
        self.end_headers()
        with lock:
            open_streams += 1
            total_connections += 1
            max_open = max(max_open, open_streams)
            print(f"    [fake {time.strftime('%H:%M:%S')}] conexão aberta de {self.client_address[1]} (abertas agora: {open_streams})", flush=True)
        try:
            while not stop_fake.is_set():
                self.wfile.write(b"Heartbeat\r\n")  # a linha só prova que a conexão está viva (o cliente exige dado a cada 3 batimentos)
                self.wfile.flush()
                time.sleep(1)
        except OSError:
            pass
        finally:
            with lock:
                open_streams -= 1
                print(f"    [fake {time.strftime('%H:%M:%S')}] conexão {self.client_address[1]} fechada (abertas agora: {open_streams})", flush=True)
            self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True


# ---- infraestrutura ----------------------------------------------------------------------------------------------------
def psql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", "postgres", "-c", sql],
                          capture_output=True, text=True)


def start_instance(name, port, mgmt):
    env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
               SPRING_PROFILES_ACTIVE="dev", SERVER_PORT=str(port), MANAGEMENT_PORT=str(mgmt),
               DEVICE_HTTP_ALLOWED_HOSTS="127.0.0.1", DH_USER="admin", DH_PASS="senha-de-teste",
               PORTARIA_DEVICES_DAHUA_EVENTS_RECONCILE_MS="3000", PORTARIA_DEVICES_DAHUA_EVENTS_LEASE_SECONDS="10",
               PORTARIA_RATE_LIMIT_API_PER_MINUTE="1000000")
    p = subprocess.Popen(["java", "-jar", "backend/target/platform-0.1.0-SNAPSHOT.jar"], env=env,
                         stdout=open(f"{LOGDIR}/e2e-two-{name}.log", "w"), stderr=subprocess.STDOUT, start_new_session=True)
    procs.append(p)
    return p


def wait_up(mgmt, seconds=120):
    for _ in range(seconds):
        try:
            if urllib.request.urlopen(f"http://localhost:{mgmt}/actuator/health", timeout=2).status == 200:
                return
        except Exception:
            time.sleep(1)
    raise SystemExit(f"instância não subiu (gestão {mgmt})")


def api(port, method, path, subject, body=None):
    token = json.load(urllib.request.urlopen(f"http://localhost:{port}/dev/token?subject={subject}"))["access_token"]
    req = urllib.request.Request(f"http://localhost:{port}{path}", method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def kill9(p):
    try:
        os.killpg(p.pid, signal.SIGKILL)
    except Exception:
        pass
    p.wait()


def wait_for(cond, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.5)
    return cond()


def observe(seconds):
    """Amostra por `seconds` e devolve o maior número de conexões abertas ao mesmo tempo nesse intervalo."""
    peak = 0
    end = time.time() + seconds
    while time.time() < end:
        with lock:
            peak = max(peak, open_streams)
        time.sleep(0.25)
    return peak


jar = "backend/target/platform-0.1.0-SNAPSHOT.jar"
if not os.path.exists(jar):
    raise SystemExit("jar não encontrado: mvn -f backend/pom.xml package -DskipTests")
assert psql(f"CREATE DATABASE {DB}").returncode == 0, "Postgres do compose não está no ar"
fake = Server(("127.0.0.1", FAKE_PORT), Fake)
threading.Thread(target=fake.serve_forever, daemon=True).start()
try:
    print("== duas instâncias no mesmo banco")
    a = start_instance("a", 8101, 8102)
    wait_up(8102)  # a primeira aplica as migrações e o seed; a segunda só depois (evita corrida no Flyway)
    b = start_instance("b", 8111, 8112)
    wait_up(8112)
    condos = api(8101, "GET", "/api/v1/condominiums", "manager-sol")["items"]
    condo = condos[0]["id"]
    api(8101, "POST", f"/api/v1/condominiums/{condo}/devices", "manager-sol",
        {"name": "Controladora falsa", "kind": "ACCESS_CONTROLLER", "adapter": "DAHUA_CGI", "entrance": "principal",
         "config": {"baseUrl": f"http://127.0.0.1:{FAKE_PORT}", "userEnv": "DH_USER", "passwordEnv": "DH_PASS"}})
    check("a controladora recebe exatamente 1 conexão de eventos", wait_for(lambda: open_streams == 1, 40), f"(abertas={open_streams})")
    peak = observe(15)
    check("com as duas instâncias ativas nunca há 2 conexões ao mesmo tempo", peak == 1 and max_open == 1, f"(pico={max_open})")
    owner = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", DB, "-At", "-c",
                            "SELECT count(*) FROM device_event_lease"], capture_output=True, text=True).stdout.strip()
    check("há uma única posse registrada no banco", owner == "1", f"(linhas={owner})")

    print("== queda abrupta (SIGKILL) das instâncias")
    kill9(a)
    ok = wait_for(lambda: open_streams == 1, 40)
    check("sem a instância A a controladora continua com 1 assinatura (a dona sobreviveu ou a B assumiu)", ok, f"(abertas={open_streams})")
    check("em nenhum momento passou de 1 conexão", max_open == 1, f"(pico={max_open})")
    kill9(b)
    check("sem nenhuma instância a conexão cai", wait_for(lambda: open_streams == 0, 40), f"(abertas={open_streams})")
    c = start_instance("c", 8121, 8122)
    wait_up(8122)
    check("uma instância nova assume depois que a posse da anterior vence", wait_for(lambda: open_streams == 1, 60), f"(abertas={open_streams})")
    check("e continua sendo só 1 conexão no total simultâneo", max_open == 1, f"(pico={max_open}, conexões abertas ao todo={total_connections})")
finally:
    stop_fake.set()
    for p in procs:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except Exception:
            pass
    time.sleep(1)
    psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
