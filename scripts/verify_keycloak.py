#!/usr/bin/env python3
"""Verifica a integração com o Keycloak de ponta a ponta (somente stdlib).

1. Cria usuários de teste no realm (um operador e um morador, ambos sem OTP configurado).
2. Faz o login pelo NAVEGADOR (formulário + cookies + PKCE): o operador deve ser levado a configurar OTP,
   o morador deve receber o código de autorização direto.
3. Sobe a API (sem perfil dev, validando tokens do Keycloak) e prova: token válido autoriza pela associação,
   token adulterado/sem token é recusado, e um usuário sem associação não acessa nada.
Requisitos: Keycloak em localhost:8180 (docker-compose.keycloak-dev.yml), Postgres em 5433, jar do backend empacotado.
"""
import base64, hashlib, html, json, os, re, secrets, subprocess, sys, time
import http.cookiejar as cookiejar
import urllib.error, urllib.parse, urllib.request

KC = "http://localhost:8180"
API = "http://localhost:8081"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "Senha-Longa-De-Teste-123"


def http(method, url, data=None, headers=None, jar=None, follow=True):
    handlers = [urllib.request.HTTPCookieProcessor(jar)] if jar is not None else []
    if not follow:
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        handlers.append(NoRedirect)
    opener = urllib.request.build_opener(*handlers)
    body = data if isinstance(data, bytes) else (urllib.parse.urlencode(data).encode() if isinstance(data, dict) else None)
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        r = opener.open(req, timeout=15)
        return r.status, dict(r.headers), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")


def admin_token():
    s, _, b = http("POST", f"{KC}/realms/master/protocol/openid-connect/token",
                   {"client_id": "admin-cli", "username": "admin", "password": os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin_dev_only"),
                    "grant_type": "password"})
    assert s == 200, b
    return json.loads(b)["access_token"]


def admin(method, path, token, body=None):
    h = {"Authorization": f"Bearer {token}"}
    data = None
    if body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    return http(method, f"{KC}/admin/realms/portaria{path}", data, h, follow=False)


def create_user(tok, username, role):
    s, _, b = admin("POST", "/users", tok, {"username": username, "email": f"{username}@example.invalid", "enabled": True,
                    "emailVerified": True, "firstName": username, "lastName": "Teste",
                    "credentials": [{"type": "password", "value": PASSWORD, "temporary": False}]})
    assert s in (201, 409), (s, b)
    s, _, b = admin("GET", f"/users?username={username}&exact=true", tok)
    uid = json.loads(b)[0]["id"]
    if role:
        s, _, b = admin("GET", f"/roles/{role}", tok)
        admin("POST", f"/users/{uid}/role-mappings/realm", tok, [json.loads(b)])
    return uid


def browser_login(username):
    """Login real pelo fluxo 'browser-mfa'. Devolve ('code', codigo) ou ('otp-setup', None)."""
    jar = cookiejar.CookieJar()
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    q = urllib.parse.urlencode({"client_id": "portaria-panel", "redirect_uri": "http://localhost:5173/callback", "response_type": "code",
                                "scope": "openid", "code_challenge": challenge, "code_challenge_method": "S256", "state": "x"})
    s, _, page = http("GET", f"{KC}/realms/portaria/protocol/openid-connect/auth?{q}", jar=jar)
    for c in jar:  # navegadores enviam cookies Secure para localhost; o cookiejar do Python não: ajuste só do cliente de teste
        c.secure = False
    action = html.unescape(re.search(r'<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"', page).group(1))
    s, headers, body = http("POST", action, {"username": username, "password": PASSWORD, "credentialId": ""}, jar=jar, follow=False)
    if s in (302, 303) and "code=" in headers.get("Location", ""):
        return "code", urllib.parse.parse_qs(urllib.parse.urlparse(headers["Location"]).query)["code"][0]
    # sem redirecionamento com código: a próxima página é a configuração do autenticador (OTP)
    if s in (302, 303):
        s, _, body = http("GET", headers["Location"], jar=jar)
    if re.search(r"kc-totp|otp|authenticator", body, re.I):
        return "otp-setup", None
    return "unknown", body[:300]


def api(method, path, token=None, body=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = f"Bearer {token}"
    return http(method, f"{API}{path}", json.dumps(body).encode() if body is not None else None, h)


def direct_grant_token(tok, username):
    """Cliente temporário só para este teste (login real das pessoas é pelo navegador, com PKCE e MFA)."""
    admin("POST", "/clients", tok, {"clientId": "cli-test", "publicClient": True, "directAccessGrantsEnabled": True, "enabled": True})
    s, _, b = http("POST", f"{KC}/realms/portaria/protocol/openid-connect/token",
                   {"client_id": "cli-test", "grant_type": "password", "username": username, "password": PASSWORD, "scope": "openid"})
    assert s == 200, b
    return json.loads(b)["access_token"]


def check(name, ok, detail=""):
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    if not ok:
        check.failed = True
check.failed = False

print("== usuários no Keycloak")
tok = admin_token()
suffix = secrets.token_hex(3)
uid_admin = create_user(tok, f"plat-admin-{suffix}", "platform-admin")
uid_op = create_user(tok, f"op-{suffix}", "operator")
uid_res = create_user(tok, f"res-{suffix}", "resident")
uid_none = create_user(tok, f"nobody-{suffix}", None)

print("== MFA no login pelo navegador")
kind, _ = browser_login(f"op-{suffix}")
check("operador sem OTP é obrigado a configurar o autenticador", kind == "otp-setup", f"(resultado: {kind})")
kind, _ = browser_login(f"res-{suffix}")
check("morador entra sem OTP (recebe o código de autorização)", kind == "code", f"(resultado: {kind})")

print("== API validando tokens do Keycloak")
VERIFY_DB = "kc_verify_" + suffix
def psql(sql):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "portaria", "-d", "postgres", "-c", sql],
                          cwd=ROOT, capture_output=True, text=True)
assert psql(f"CREATE DATABASE {VERIFY_DB}").returncode == 0  # banco limpo: o bootstrap só atua em instalação nova
env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{VERIFY_DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
           OIDC_ISSUER_URI=f"{KC}/realms/portaria", OIDC_JWK_SET_URI=f"{KC}/realms/portaria/protocol/openid-connect/certs",
           CREDENTIAL_HMAC_SECRET="kc-verify-secret-0123456789-abcdefghijklmnopqr", AGENT_POLICY_MASTER_SECRET="kc-verify-agent-master-0123456789-abcdefghij",
           BOOTSTRAP_PLATFORM_ADMIN_SUBJECT=uid_admin, BOOTSTRAP_PLATFORM_ADMIN_EMAIL=f"plat-admin-{suffix}@example.invalid",
           SPRING_PROFILES_ACTIVE="")
log = open(os.path.join(os.environ.get("TMPDIR", "/tmp"), "kc-verify-api.log"), "w")
proc = subprocess.Popen(["java", "-jar", os.path.join(ROOT, "backend/target/platform-0.1.0-SNAPSHOT.jar")], env=env, stdout=log, stderr=log)
try:
    for _ in range(90):
        try:
            if http("GET", "http://localhost:8082/actuator/health")[0] == 200:
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise SystemExit("API não subiu")
    t_admin = direct_grant_token(tok, f"plat-admin-{suffix}")
    t_none = direct_grant_token(tok, f"nobody-{suffix}")
    s, _, _ = api("GET", "/api/v1/condominiums")
    check("sem token -> 401", s == 401, f"({s})")
    bad = t_admin[:-4] + ("AAAA" if not t_admin.endswith("AAAA") else "BBBB")
    s, _, _ = api("GET", "/api/v1/condominiums", bad)
    check("token adulterado -> 401", s == 401, f"({s})")
    s, _, b = api("POST", "/api/v1/companies", t_admin, {"name": f"Empresa KC {suffix}", "taxId": f"kc{suffix}0000000"})
    check("admin da plataforma (bootstrap) cria empresa com token do Keycloak -> 201", s == 201, f"({s}) {b[:80] if s != 201 else ''}")
    s, _, _ = api("POST", "/api/v1/companies", t_none, {"name": "X", "taxId": "x"})
    check("usuário autenticado no Keycloak mas sem associação -> 403", s == 403, f"({s})")
    company = json.loads(api("GET", "/api/v1/companies", t_admin)[2])["items"]
    company_id = [c["id"] for c in company if c["name"] == f"Empresa KC {suffix}"][0]
    s, _, b = api("POST", f"/api/v1/companies/{company_id}/admins", t_admin,
                  {"externalSubject": uid_op, "email": f"op-{suffix}@example.invalid", "displayName": "Operador KC"})
    check("provisionamento vincula o sub do Keycloak a um papel -> 201", s == 201, f"({s})")
    t_op = direct_grant_token(tok, f"op-{suffix}")
    s, _, b = api("GET", "/api/v1/condominiums", t_op)
    check("o novo administrador de empresa já é reconhecido", s == 200, f"({s})")
finally:
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()
    psql(f"DROP DATABASE IF EXISTS {VERIFY_DB} WITH (FORCE)")
print("RESULTADO:", "FALHOU" if check.failed else "OK")
sys.exit(1 if check.failed else 0)
