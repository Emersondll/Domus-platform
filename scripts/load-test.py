#!/usr/bin/env python3
"""Teste de carga simples (somente stdlib): latência e vazão de endpoints representativos.

Uso: scripts/load-test.py [--base http://localhost:8081] [--seconds 15] [--concurrency 20]
Requer a API com perfil dev (usa /dev/token) e com os limites de taxa elevados, por exemplo:
  PORTARIA_RATE_LIMIT_API_PER_MINUTE=1000000 PORTARIA_RATE_LIMIT_VALIDATE_PER_MINUTE=1000000 PORTARIA_RATE_LIMIT_DEVICE_PER_MINUTE=1000000
Limitações: gerador e servidor na mesma máquina; cliente em Python (GIL). Os números são um PISO de capacidade.
"""
import argparse, sys, http.client, json, statistics, threading, time, urllib.parse, uuid

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:8081")
ap.add_argument("--seconds", type=int, default=15)
ap.add_argument("--concurrency", type=int, default=20)
args = ap.parse_args()
u = urllib.parse.urlparse(args.base)


def conn():
    return http.client.HTTPConnection(u.hostname, u.port, timeout=30)


def call(c, method, path, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    payload = json.dumps(body) if body is not None else None
    try:
        c.request(method, path, payload, h)
        r = c.getresponse()
    except (http.client.RemoteDisconnected, ConnectionResetError, BrokenPipeError):
        # o servidor fecha conexões ociosas (keep-alive de ~20 s); a de preparação fica parada durante os cenários
        c.close()
        c.request(method, path, payload, h)
        r = c.getresponse()
    data = r.read()
    return r.status, data


def token(subject):
    c = conn()
    s, d = call(c, "GET", f"/dev/token?subject={subject}")
    assert s == 200, "perfil dev necessário"
    return json.loads(d)["access_token"]


op = token("operator-sol")
mgr = token("manager-sol")
c0 = conn()
condo = json.loads(call(c0, "GET", "/api/v1/condominiums", mgr)[1])["items"][0]["id"]


# o limitador padrão distorceria tudo: 429 aparece como "erro" do sistema. Confere antes de medir.
for _ in range(400):
    if call(c0, "GET", "/api/v1/condominiums", mgr)[0] == 429:
        sys.exit("limitador de taxa ativo (429): reinicie a API com PORTARIA_RATE_LIMIT_*_PER_MINUTE=1000000 (ver cabeçalho) "
                 "e espere ~75 s se o limitador já estava quente")


def scenario(name, fn):
    lat, errors, done = [], [0], threading.Event()
    lock = threading.Lock()

    def worker():
        c = conn()
        mine = []
        err = 0
        while not done.is_set():
            t = time.perf_counter()
            try:
                ok = fn(c)
            except Exception:
                ok, c = False, conn()
            mine.append((time.perf_counter() - t) * 1000)
            err += 0 if ok else 1
        with lock:
            lat.extend(mine)
            errors[0] += err

    threads = [threading.Thread(target=worker) for _ in range(args.concurrency)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    time.sleep(args.seconds)
    done.set()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - start
    lat.sort()
    q = lambda p: lat[min(len(lat) - 1, int(len(lat) * p))]
    print(f"{name:<34} {len(lat)/elapsed:8.0f} req/s   p50 {q(.50):6.1f} ms   p95 {q(.95):6.1f} ms   p99 {q(.99):6.1f} ms   erros {errors[0]}/{len(lat)}")


print(f"base={args.base} concorrência={args.concurrency} duração={args.seconds}s por cenário\n")
scenario("GET condomínios (leitura+auth)", lambda c: call(c, "GET", "/api/v1/condominiums", mgr)[0] == 200)
scenario("GET moradores paginado", lambda c: call(c, "GET", f"/api/v1/condominiums/{condo}/residents?size=20", mgr)[0] == 200)
scenario("GET visão geral do operador", lambda c: call(c, "GET", "/api/v1/operator/overview", op)[0] == 200)
scenario("POST validar credencial (escrita)", lambda c: call(c, "POST", f"/api/v1/condominiums/{condo}/access/validate", op,
                                                             {"token": f"{uuid.uuid4()}.x", "entrance": "portaria"})[0] == 200)

# "Meus acessos": ingestão de eventos de leitor com identidade (escrita no histórico pessoal) e consulta do titular (auditada).
res = token("resident-sol")
residency = json.loads(call(c0, "GET", "/api/v1/me/residencies", res)[1])[0]
reader = json.loads(call(c0, "POST", f"/api/v1/condominiums/{condo}/devices", mgr,
                         {"name": f"Leitor carga {uuid.uuid4().hex[:6]}", "kind": "ACCESS_CONTROLLER", "adapter": "SIMULATOR",
                          "entrance": "portaria", "config": {"direction": "IN"}})[1])
ref = f"CARGA-{uuid.uuid4().hex[:8]}"
assert call(c0, "POST", f"/api/v1/condominiums/{condo}/residents/{residency['residentId']}/access-identities", mgr,
            {"kind": "CARD", "ref": ref})[0] == 201


def reader_event(c):
    body = json.dumps({"type": "DOOR_OPENED", "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "identityRef": ref})
    c.request("POST", f"/api/v1/devices/{reader['id']}/events", body,
              {"Content-Type": "application/json", "X-Device-Key": reader["apiKey"]})
    r = c.getresponse()
    r.read()
    return r.status == 202


scenario("POST evento de leitor c/ identidade", reader_event)
scenario("GET meus acessos (auditado)", lambda c: call(c, "GET", f"/api/v1/condominiums/{condo}/me/accesses?size=20", res)[0] == 200)

# Vídeo ao vivo: abrir a sessão (grava a sessão, aplica o teto por usuário e audita) e a validação que o servidor de mídia faz a
# CADA requisição de playlist/trecho (consulta à sessão no banco). Câmera publicada por agente: não precisa de MediaMTX.
cam = json.loads(call(c0, "POST", f"/api/v1/condominiums/{condo}/devices", mgr,
                      {"name": f"Camera carga {uuid.uuid4().hex[:6]}", "kind": "CAMERA", "adapter": "ONVIF", "entrance": "portaria",
                       "config": {"videoSource": "AGENT"}})[1])
scenario("POST abrir sessão de vídeo", lambda c: call(c, "POST", f"/api/v1/condominiums/{condo}/devices/{cam['id']}/stream-session", mgr)[0] == 200)
hls = json.loads(call(c0, "POST", f"/api/v1/condominiums/{condo}/devices/{cam['id']}/stream-session", mgr)[1])["hlsUrl"]
media_query = hls.split("?", 1)[1]


def media_auth(c):
    c.request("POST", "/internal/media/auth", json.dumps({"action": "read", "path": f"cam-{cam['id']}", "query": media_query, "password": ""}),
              {"Content-Type": "application/json"})
    r = c.getresponse()
    r.read()
    return r.status == 200


scenario("POST validação do token de vídeo", media_auth)
