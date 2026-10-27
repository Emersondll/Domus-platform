#!/usr/bin/env python3
"""Login OIDC (Authorization Code + PKCE) em NAVEGADOR REAL contra o Keycloak de desenvolvimento.

O que `e2e-browser.py` não cobre (ele usa o login de desenvolvimento) e `verify_keycloak.py` cobre só por HTTP:
- o morador entra pelo formulário do Keycloak, volta ao app com o código, o app troca por token e a API (sem perfil dev) aceita;
- o operador é obrigado a CADASTRAR o autenticador (TOTP) no primeiro login; o código é calculado aqui (RFC 6238) a partir do segredo
  exibido pela tela, e nos logins seguintes o OTP é pedido;
- a renovação silenciosa do token em segundo plano (o tempo de vida do token do realm é reduzido para 30 s durante o teste);
- sair do painel encerra a sessão no Keycloak (um novo acesso pede login de novo);
- nenhum token em localStorage/sessionStorage.
Sobe sozinho um banco novo, a API (sem perfil dev, validando tokens do Keycloak) e os dois Vite. Usuários e dados são sintéticos.
Requisitos: Keycloak em localhost:8180 (`docker compose -f docker-compose.keycloak-dev.yml up -d`), Postgres em 5433, jar do backend,
Playwright (`pip install playwright && playwright install chromium`), Node >= 22.12 e `npm install` em web/.
"""
import base64, glob, hashlib, hmac, json, os, re, secrets, shutil, signal, struct, subprocess, sys, time
import urllib.error, urllib.parse, urllib.request

from playwright.sync_api import expect, sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
KC = "http://localhost:8180"
API = "http://localhost:8081"
PASSWORD = "Senha-Longa-De-Teste-123"
SUFFIX = secrets.token_hex(3)
DB = f"e2e_oidc_{SUFFIX}"
LOGDIR = os.environ.get("TMPDIR", "/tmp")
procs: list[subprocess.Popen] = []
failed = False


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def http(method, url, data=None, headers=None):
    body = data if isinstance(data, bytes) else (urllib.parse.urlencode(data).encode() if isinstance(data, dict) else None)
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def kc_admin_token():
    s, b = http("POST", f"{KC}/realms/master/protocol/openid-connect/token",
                {"client_id": "admin-cli", "username": "admin", "password": os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin_dev_only"),
                 "grant_type": "password"})
    if s != 200:
        raise SystemExit(f"Keycloak indisponível ({s}): docker compose -f docker-compose.keycloak-dev.yml up -d")
    return json.loads(b)["access_token"]


def kc(method, path, token, body=None):
    h = {"Authorization": f"Bearer {token}"}
    data = None
    if body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    return http(method, f"{KC}/admin/realms/portaria{path}", data, h)


def create_user(tok, username, role):
    s, b = kc("POST", "/users", tok, {"username": username, "email": f"{username}@example.invalid", "enabled": True,
              "emailVerified": True, "firstName": username, "lastName": "Teste",
              "credentials": [{"type": "password", "value": PASSWORD, "temporary": False}]})
    assert s in (201, 409), (s, b)
    uid = json.loads(kc("GET", f"/users?username={username}&exact=true", tok)[1])[0]["id"]
    if role:
        r = json.loads(kc("GET", f"/roles/{role}", tok)[1])
        kc("POST", f"/users/{uid}/role-mappings/realm", tok, [r])
    return uid


def api_token(tok, username):
    """Token só para PREPARAR o cenário (cliente temporário com audiência da API); o login que se testa é o do navegador."""
    kc("POST", "/clients", tok, {"clientId": "cli-seed", "publicClient": True, "directAccessGrantsEnabled": True, "enabled": True,
       "protocolMappers": [{"name": "aud", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                            "config": {"included.custom.audience": "portaria-api", "access.token.claim": "true", "id.token.claim": "false"}}]})
    s, b = http("POST", f"{KC}/realms/portaria/protocol/openid-connect/token",
                {"client_id": "cli-seed", "grant_type": "password", "username": username, "password": PASSWORD, "scope": "openid"})
    assert s == 200, b
    return json.loads(b)["access_token"]


def api(method, path, token, body=None):
    s, b = http(method, f"{API}{path}", json.dumps(body).encode() if body is not None else None,
                {"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    assert s in (200, 201, 204), f"{method} {path} -> {s} {b[:200]}"
    return json.loads(b) if b else None


def totp(secret_b32, at=None):
    """TOTP RFC 6238 (HMAC-SHA1, 6 dígitos, 30 s): o mesmo padrão que o realm configura."""
    key = base64.b32decode(secret_b32.replace(" ", "").upper() + "=" * (-len(secret_b32.replace(" ", "")) % 8))
    counter = int((at or time.time()) // 30)
    mac = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    off = mac[-1] & 0x0F
    return f"{(struct.unpack('>I', mac[off:off + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def psql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", "postgres", "-c", sql],
                          capture_output=True, text=True)


def wait_http(url, seconds=90):
    for _ in range(seconds):
        try:
            if urllib.request.urlopen(url, timeout=2).status == 200:
                return
        except Exception:
            time.sleep(1)
    raise SystemExit(f"não subiu: {url}")


def node_path():
    best = None
    for d in glob.glob(os.path.expanduser("~/.nvm/versions/node/v*/bin")):
        m = re.search(r"v(\d+)\.(\d+)", d)
        if m and (int(m[1]), int(m[2])) >= (22, 12):
            best = d
    return best


def cleanup(tok):
    for p in procs:
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except Exception:
            pass
    time.sleep(2)
    if shutil.which("fuser"):
        subprocess.run(["fuser", "-k", "5173/tcp", "5174/tcp"], capture_output=True)
    psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")
    try:
        kc("PUT", "", tok, {"realm": "portaria", "accessTokenLifespan": 300})  # devolve o tempo de vida original
        for username in (f"adm-{SUFFIX}", f"ca-{SUFFIX}", f"op-{SUFFIX}", f"res-{SUFFIX}"):
            users = json.loads(kc("GET", f"/users?username={username}&exact=true", tok)[1])
            if users:
                kc("DELETE", f"/users/{users[0]['id']}", tok)
    except Exception:
        pass


def oidc_config(client):
    return json.dumps({"authority": f"{KC}/realms/portaria", "clientId": client})


def keycloak_login(page, username):
    expect(page.locator("#username")).to_be_visible(timeout=20000)
    page.fill("#username", username)
    page.fill("#password", PASSWORD)
    page.click("#kc-login")


def shot(page, name):
    try:
        page.screenshot(path=f"{LOGDIR}/e2e-oidc-{name}.png")
    except Exception:
        pass


jar = "backend/target/platform-0.1.0-SNAPSHOT.jar"
if not os.path.exists(jar):
    raise SystemExit("jar não encontrado: mvn -f backend/pom.xml package -DskipTests")
tok = kc_admin_token()
print("== preparação (usuários no Keycloak, banco novo, API sem perfil dev, Vite)")
uid_adm = create_user(tok, f"adm-{SUFFIX}", "platform-admin")
uid_ca = create_user(tok, f"ca-{SUFFIX}", "company-admin")
uid_op = create_user(tok, f"op-{SUFFIX}", "operator")
uid_res = create_user(tok, f"res-{SUFFIX}", "resident")
kc("PUT", "", tok, {"realm": "portaria", "accessTokenLifespan": 30})  # renovação visível em segundos
assert psql(f"CREATE DATABASE {DB}").returncode == 0, "Postgres do compose não está no ar"
env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
           OIDC_AUDIENCE="portaria-api", OIDC_ISSUER_URI=f"{KC}/realms/portaria",
           OIDC_JWK_SET_URI=f"{KC}/realms/portaria/protocol/openid-connect/certs",
           FIELD_ENCRYPTION_KEYS="kcb:" + base64.b64encode(hashlib.sha256(b"kc-browser-field-key").digest()).decode(),
           BLIND_INDEX_KEY="kc-browser-blind-index-key-0123456789-abc",
           CREDENTIAL_HMAC_SECRET="kc-browser-secret-0123456789-abcdefghijklmnopqr",
           AGENT_POLICY_MASTER_SECRET="kc-browser-agent-master-0123456789-abcdefghij",
           BOOTSTRAP_PLATFORM_ADMIN_SUBJECT=uid_adm, BOOTSTRAP_PLATFORM_ADMIN_EMAIL=f"adm-{SUFFIX}@example.invalid",
           SPRING_PROFILES_ACTIVE="")
web_env = dict(os.environ)
if node_path():
    web_env["PATH"] = node_path() + os.pathsep + web_env["PATH"]
try:
    procs.append(subprocess.Popen(["java", "-jar", jar], env=env, stdout=open(f"{LOGDIR}/e2e-oidc-api.log", "w"),
                                  stderr=subprocess.STDOUT, start_new_session=True))
    for ws in ("panel", "resident"):
        procs.append(subprocess.Popen(["npm", "run", "dev", "-w", ws, "--", "--strictPort"], cwd="web", env=web_env,
                                      stdout=open(f"{LOGDIR}/e2e-oidc-{ws}.log", "w"), stderr=subprocess.STDOUT, start_new_session=True))
    wait_http("http://localhost:8082/actuator/health")
    wait_http("http://localhost:5173")
    wait_http("http://localhost:5174")

    t_adm = api_token(tok, f"adm-{SUFFIX}")
    company = api("POST", "/api/v1/companies", t_adm, {"name": f"Empresa OIDC {SUFFIX}", "taxId": f"oidc{SUFFIX}00000"})
    api("POST", f"/api/v1/companies/{company['id']}/admins", t_adm,
        {"externalSubject": uid_ca, "email": f"ca-{SUFFIX}@example.invalid", "displayName": "Admin Empresa OIDC"})
    t_ca = api_token(tok, f"ca-{SUFFIX}")
    condo = api("POST", "/api/v1/condominiums", t_ca, {"operatorCompanyId": company["id"], "name": f"Condomínio OIDC {SUFFIX}"})
    base = f"/api/v1/condominiums/{condo['id']}"
    api("POST", f"{base}/members", t_ca, {"role": "OPERATOR", "account": {"externalSubject": uid_op,
        "email": f"op-{SUFFIX}@example.invalid", "displayName": "Operador OIDC"}})
    api("POST", f"{base}/members", t_ca, {"role": "CONDO_MANAGER", "account": {"externalSubject": secrets.token_hex(8),
        "email": f"mgr-{SUFFIX}@example.invalid", "displayName": "Síndico OIDC"}})
    block = api("POST", f"{base}/blocks", t_ca, {"name": "A"})
    unit = api("POST", f"{base}/units", t_ca, {"blockId": block["id"], "number": "101"})
    resident = api("POST", f"{base}/residents", t_ca, {"unitId": unit["id"], "fullName": "Morador OIDC Sintético",
                   "email": f"res-{SUFFIX}@example.invalid", "residentType": "OWNER"})
    api("POST", f"{base}/residents/{resident['id']}/account", t_ca, {"externalSubject": uid_res,
        "email": f"res-{SUFFIX}@example.invalid", "displayName": "Morador OIDC"})

    with sync_playwright() as p:
        browser = p.chromium.launch()

        def new_page(client, port):
            ctx = browser.new_context()
            page = ctx.new_page()
            page.route("**/config.json", lambda r: r.fulfill(status=200, content_type="application/json", body=oidc_config(client)))
            seen = {"api": [], "refresh": 0, "bad": [], "token_calls": [], "expires_in": None}

            def on_response(res):
                if "/protocol/openid-connect/token" in res.url and res.status == 200:
                    try:
                        seen["expires_in"] = res.json().get("expires_in")
                    except Exception:
                        pass
                if "/api/v1/" in res.url:
                    seen["api"].append(res.status)
                    if res.status in (401, 403):
                        seen["bad"].append(f"{res.status} {res.url}")

            def on_request(req):
                if "/protocol/openid-connect/token" in req.url:
                    grant = re.search(r"grant_type=([a-z_]+)", req.post_data or "")
                    seen["token_calls"].append(grant.group(1) if grant else "?")
                    if grant and grant.group(1) == "refresh_token":
                        seen["refresh"] += 1

            page.on("response", on_response)
            page.on("request", on_request)
            page.goto(f"http://localhost:{port}/")
            return ctx, page, seen

        print("== morador: login pelo Keycloak e renovação do token")
        ctx, res, seen = new_page("portaria-resident", 5174)
        res.get_by_role("button", name="Entrar").click()
        keycloak_login(res, f"res-{SUFFIX}")
        try:
            expect(res.get_by_text(re.compile("Condomínio OIDC"))).to_be_visible(timeout=20000)
            check("o morador volta do Keycloak ao app e a API (sem perfil dev) aceita o token", True)
        except AssertionError:
            shot(res, "morador")
            check("o morador volta do Keycloak ao app e a API (sem perfil dev) aceita o token", False, res.url)
        check("nenhuma resposta 401/403 da API no login do morador", not seen["bad"], str(seen["bad"][:3]))
        check("nenhum token em localStorage/sessionStorage (morador)",
              "eyJ" not in res.evaluate("() => JSON.stringify([localStorage, sessionStorage])"))
        res.wait_for_timeout(50_000)  # o token vale 30 s: o app precisa ter renovado em segundo plano (wait_for_timeout processa os eventos)
        check("o Keycloak emitiu o token curto pedido (30 s)", seen["expires_in"] == 30, f"(expires_in={seen['expires_in']})")
        check("o token foi renovado em segundo plano (refresh_token)", seen["refresh"] >= 1, f"(chamadas ao endpoint de token: {seen['token_calls']})")
        res.reload()
        try:
            # a memória some no recarregamento: o app mostra "Entrar" e o SSO do Keycloak devolve o código sem pedir senha
            res.get_by_role("button", name="Entrar").click(timeout=10000)
            expect(res.get_by_text(re.compile("Condomínio OIDC"))).to_be_visible(timeout=20000)
            check("recarregar e tocar em Entrar volta ao app sem pedir senha (SSO do Keycloak)", True)
        except AssertionError:
            shot(res, "morador-reload")
            check("recarregar e tocar em Entrar volta ao app sem pedir senha (SSO do Keycloak)", False, res.url)
        check("nenhuma resposta 401/403 da API depois da renovação e do recarregamento", not seen["bad"], str(seen["bad"][:3]))
        ctx.close()

        print("== operador: cadastro obrigatório do autenticador (TOTP) e logins seguintes")
        ctx, pan, seen = new_page("portaria-panel", 5173)
        pan.get_by_role("button", name="Entrar").click()
        keycloak_login(pan, f"op-{SUFFIX}")
        secret = None
        try:
            expect(pan.locator("#kc-totp-secret-key, #mode-manual").first).to_be_visible(timeout=20000)
            if pan.locator("#mode-manual").count():
                pan.click("#mode-manual")
            secret = pan.locator("#kc-totp-secret-key").inner_text().strip()
            check("o primeiro login do operador exige cadastrar o autenticador", bool(secret))
        except AssertionError:
            shot(pan, "operador-otp")
            check("o primeiro login do operador exige cadastrar o autenticador", False, pan.url)
        if secret:
            pan.fill("#totp", totp(secret))
            if pan.locator("#userLabel").count():
                pan.fill("#userLabel", "e2e")
            pan.click("#saveTOTPBtn")
            try:
                expect(pan.get_by_text(re.compile("Condomínio OIDC"))).to_be_visible(timeout=20000)
                check("com o código do autenticador o operador entra no painel", True)
            except AssertionError:
                shot(pan, "operador-painel")
                check("com o código do autenticador o operador entra no painel", False, pan.url)
            check("nenhuma resposta 401/403 da API no login do operador", not seen["bad"], str(seen["bad"][:3]))
            check("nenhum token em localStorage/sessionStorage (painel)",
                  "eyJ" not in pan.evaluate("() => JSON.stringify([localStorage, sessionStorage])"))

            pan.get_by_role("button", name="Sair").click()
            try:
                expect(pan.locator("#username, button:has-text('Entrar')").first).to_be_visible(timeout=20000)
                check("sair do painel volta à tela de entrada", True)
            except AssertionError:
                shot(pan, "operador-sair")
                check("sair do painel volta à tela de entrada", False, pan.url)
            # novo acesso: a sessão do Keycloak foi encerrada, então pede senha e agora o OTP (não mais o cadastro)
            pan.goto("http://localhost:5173/")
            if pan.get_by_role("button", name="Entrar").count():
                pan.get_by_role("button", name="Entrar").click()
            expect(pan.locator("#username")).to_be_visible(timeout=20000)
            check("depois de sair, o painel pede o login de novo (sessão encerrada no Keycloak)", True)
            pan.fill("#username", f"op-{SUFFIX}")
            pan.fill("#password", PASSWORD)
            pan.click("#kc-login")
            expect(pan.locator("#otp")).to_be_visible(timeout=20000)
            # o Keycloak recusa reaproveitar o código do mesmo intervalo de 30 s: espera o próximo
            wait = 30 - (time.time() % 30) + 1
            pan.wait_for_timeout(int(wait * 1000))
            pan.fill("#otp", totp(secret))
            pan.click("#kc-login")
            try:
                expect(pan.get_by_text(re.compile("Condomínio OIDC"))).to_be_visible(timeout=20000)
                check("o segundo login do operador pede o OTP (não o cadastro) e entra", True)
            except AssertionError:
                shot(pan, "operador-otp2")
                check("o segundo login do operador pede o OTP (não o cadastro) e entra", False, pan.url)
            # OTP errado é recusado e a tela continua pedindo o código
            pan.get_by_role("button", name="Sair").click()
            pan.goto("http://localhost:5173/")
            if pan.get_by_role("button", name="Entrar").count():
                pan.get_by_role("button", name="Entrar").click()
            keycloak_login(pan, f"op-{SUFFIX}")
            expect(pan.locator("#otp")).to_be_visible(timeout=20000)
            pan.fill("#otp", "000000")
            pan.click("#kc-login")
            expect(pan.locator("#otp")).to_be_visible(timeout=20000)
            check("um código de autenticador errado é recusado", "Condomínio OIDC" not in pan.content())
        ctx.close()
        browser.close()
finally:
    cleanup(tok)
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
