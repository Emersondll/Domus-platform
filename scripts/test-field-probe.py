#!/usr/bin/env python3
"""Prova do roteiro de campo (scripts/field-probe-dahua.py) contra um equipamento Dahua FALSO (stdlib) que exige Digest de verdade,
emite eventos com dados pessoais fictícios e (opcionalmente) devolve o identificador do openDoor no evento.
Confere: leitura sem abrir porta, sanitização (nenhum dado pessoal no arquivo), detecção do eco do identificador, a falta do eco e a falha de senha."""
import hashlib, os, re, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
USER, PASS, REALM, NONCE = "admin", "Senha-De-Teste-1", "Login to FAKE", "n0nc3-fake"
state = {"echo": True, "opened": [], "token": None}
failed = False


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


class Fake(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def authorized(self):
        h = self.headers.get("Authorization", "")
        if not h.startswith("Digest "):
            return False
        f = dict(re.findall(r'(\w+)="?([^",]+)"?', h[7:]))
        ha1, ha2 = md5(f"{USER}:{REALM}:{PASS}"), md5(f"GET:{f.get('uri')}")
        want = md5(f"{ha1}:{NONCE}:{f.get('nc')}:{f.get('cnonce')}:{f.get('qop')}:{ha2}")
        return f.get("username") == USER and f.get("response") == want and f.get("uri") == self.path

    def do_GET(self):
        if not self.authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", f'Digest realm="{REALM}", qop="auth", nonce="{NONCE}"')
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if "openDoor" in self.path:
            state["opened"].append(self.path)
            m = re.search(r"UserID=([^&]+)", self.path)
            state["token"] = m.group(1) if m else None
            self.reply(b"OK\r\n")
        elif "magicBox" in self.path:
            self.reply(b"deviceType=ASI-FAKE\r\nserialNumber=SERIAL-SECRETO-123\r\nsoftwareVersion=1.2.3\r\n")
        elif "eventManager" in self.path:
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=b")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                for i in range(60):
                    self.wfile.write(b"Heartbeat\r\n")
                    self.wfile.write(b'Code=AccessControl;action=Pulse;index=0;data={"CardNo":"0099887766","CardName":"JOAO TESTE",'
                                     b'"UserID":"7","Status":1,"Method":1,"ReaderID":"1"}\r\n')
                    if state["token"] and state["echo"]:
                        self.wfile.write(('Code=AccessControl;action=Pulse;index=0;data={"UserID":"%s","Status":1,"Method":4}\r\n'
                                          % state["token"]).encode())
                    self.wfile.flush()
                    time.sleep(0.5)
            except OSError:
                pass
            self.close_connection = True
        else:
            self.reply(b"")

    def reply(self, body):
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(ThreadingHTTPServer):
    daemon_threads = True


srv = Server(("127.0.0.1", 0), Fake)
threading.Thread(target=srv.serve_forever, daemon=True).start()
port = srv.server_address[1]


def probe(out, *extra, password=PASS):
    env = dict(os.environ, DAHUA_PASSWORD=password)
    return subprocess.run([sys.executable, os.path.join(ROOT, "scripts/field-probe-dahua.py"), "--host", "127.0.0.1", "--port", str(port),
                           "--user", USER, "--out", out, *extra], capture_output=True, text=True, env=env, timeout=120)


with tempfile.TemporaryDirectory() as tmp:
    print("== leitura (sem abrir a porta)")
    out = os.path.join(tmp, "ro")
    r = probe(out, "--seconds", "3")
    rep = open(os.path.join(out, "report.md")).read()
    ev = open(os.path.join(out, "events.sanitized.txt")).read()
    check("o roteiro conclui com Digest real", r.returncode == 0, r.stderr[-200:])
    check("não abriu a porta", state["opened"] == [])
    check("o relatório lista o Code e os campos do data", "Code=AccessControl" in rep and "CardNo" in rep and "UserID" in rep)
    check("o número de série não vai para o relatório", "SERIAL-SECRETO" not in rep)
    check("nenhum dado pessoal nos eventos gravados", all(x not in ev for x in ("0099887766", "JOAO", "TESTE")), ev[:120])
    check("os nomes dos campos e os valores não pessoais ficam", '"CardNo": "<redigido>"' in ev and '"Status": "1"' in ev)
    check("a senha não aparece em nenhum arquivo", PASS not in rep and PASS not in ev)

    print("== abrindo a porta, equipamento que devolve o identificador")
    out = os.path.join(tmp, "open")
    r = probe(out, "--seconds", "2", "--open-door", "--yes-open")
    rep = open(os.path.join(out, "report.md")).read()
    ev = open(os.path.join(out, "events.sanitized.txt")).read()
    check("enviou um openDoor com identificador do teste", len(state["opened"]) == 1 and "UserID=portaria-field-" in state["opened"][0])
    check("o relatório diz em qual campo o identificador voltou", "voltou no evento, no campo `UserID`" in rep, rep[-300:])
    check("o identificador do teste aparece marcado, não o valor", "<identificador do teste>" in ev and "portaria-field-" not in ev)

    print("== equipamento que NÃO devolve o identificador")
    state.update(echo=False, opened=[], token=None)
    out = os.path.join(tmp, "noecho")
    probe(out, "--seconds", "2", "--open-door", "--yes-open")
    rep = open(os.path.join(out, "report.md")).read()
    check("o relatório avisa que a central não confirmaria (UNKNOWN)", "NÃO voltou" in rep and "UNKNOWN" in rep)

    print("== senha errada")
    out = os.path.join(tmp, "bad")
    r = probe(out, "--seconds", "2", password="errada")
    check("falha com mensagem clara e relatório", r.returncode != 0 and "Nenhuma resposta 200" in open(os.path.join(out, "report.md")).read())
    r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/field-probe-dahua.py"), "--host", "127.0.0.1", "--user", "x"],
                       capture_output=True, text=True, env={k: v for k, v in os.environ.items() if k != "DAHUA_PASSWORD"})
    check("sem DAHUA_PASSWORD não roda (a senha não vai para o histórico)", r.returncode != 0 and "DAHUA_PASSWORD" in r.stderr)

srv.shutdown()
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
