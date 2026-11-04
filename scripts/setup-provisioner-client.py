#!/usr/bin/env python3
"""Cria (ou corrige) o client `portaria-provisioner` num Keycloak JÁ EXISTENTE (somente stdlib).

O realm só é importado na primeira subida; em um Keycloak que já roda, este script faz o que o import faria:
client confidencial só com "service accounts", escopo completo desligado, e à conta de serviço os papéis
`manage-users`, `query-users` e `view-users` de `realm-management` (com o mesmo escopo no client).
Idempotente: pode ser repetido; atualiza o segredo e confere os papéis.

Uso:
  KEYCLOAK_URL=https://auth.exemplo.com.br KEYCLOAK_ADMIN_USER=admin KEYCLOAK_ADMIN_PASSWORD=... \
  PROVISIONER_CLIENT_SECRET=<o mesmo valor de deploy/.env.prod> python3 scripts/setup-provisioner-client.py [realm]
O segredo vem do ambiente (nunca como argumento, para não ir ao histórico do shell).
"""
import json, os, sys, urllib.error, urllib.parse, urllib.request

KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8180").rstrip("/")
REALM = sys.argv[1] if len(sys.argv) > 1 else "portaria"
CLIENT_ID = "portaria-provisioner"
ROLES = ["manage-users", "query-users", "view-users"]


def call(method, url, token=None, body=None, form=None):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def fail(msg):
    print(f"ERRO: {msg}", file=sys.stderr)
    sys.exit(1)


secret = os.environ.get("PROVISIONER_CLIENT_SECRET", "")
if len(secret) < 32:
    fail("defina PROVISIONER_CLIENT_SECRET com pelo menos 32 caracteres (o mesmo valor de deploy/.env.prod)")
password = os.environ.get("KEYCLOAK_ADMIN_PASSWORD")
if not password:
    fail("defina KEYCLOAK_ADMIN_PASSWORD")

s, tok = call("POST", f"{KC}/realms/master/protocol/openid-connect/token", form={
    "client_id": "admin-cli", "grant_type": "password",
    "username": os.environ.get("KEYCLOAK_ADMIN_USER", "admin"), "password": password})
if s != 200:
    fail(f"login de administrador recusado ({s})")
T = tok["access_token"]
A = f"{KC}/admin/realms/{REALM}"

wanted = {
    "clientId": CLIENT_ID, "name": "Criação de contas pela API", "enabled": True, "protocol": "openid-connect",
    "publicClient": False, "clientAuthenticatorType": "client-secret", "secret": secret,
    "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
    "serviceAccountsEnabled": True, "fullScopeAllowed": False,
}
s, found = call("GET", f"{A}/clients?clientId={CLIENT_ID}", T)
if s != 200:
    fail(f"não consegui consultar o realm '{REALM}' ({s})")
if found:
    cid = found[0]["id"]
    s, cur = call("GET", f"{A}/clients/{cid}", T)
    s, b = call("PUT", f"{A}/clients/{cid}", T, {**cur, **wanted})
    action = "atualizado"
else:
    s, b = call("POST", f"{A}/clients", T, wanted)
    action = "criado"
if s not in (200, 201, 204):
    fail(f"client não {action} ({s}): {b}")
s, found = call("GET", f"{A}/clients?clientId={CLIENT_ID}", T)
cid = found[0]["id"]

s, rm = call("GET", f"{A}/clients?clientId=realm-management", T)
rm_id = rm[0]["id"]
s, sa = call("GET", f"{A}/clients/{cid}/service-account-user", T)
if s != 200:
    fail(f"conta de serviço ausente ({s})")
sa_id = sa["id"]

roles = []
for name in ROLES:
    s, r = call("GET", f"{A}/clients/{rm_id}/roles/{name}", T)
    if s != 200:
        fail(f"papel {name} não existe em realm-management ({s})")
    roles.append(r)

s, b = call("POST", f"{A}/users/{sa_id}/role-mappings/clients/{rm_id}", T, roles)
if s not in (200, 204):
    fail(f"papéis não atribuídos à conta de serviço ({s}): {b}")
s, b = call("POST", f"{A}/clients/{cid}/scope-mappings/clients/{rm_id}", T, roles)
if s not in (200, 204):
    fail(f"escopo do client não ajustado ({s}): {b}")

s, have = call("GET", f"{A}/users/{sa_id}/role-mappings/clients/{rm_id}", T)
got = sorted(r["name"] for r in have)
if not set(ROLES) <= set(got):
    fail(f"papéis finais inesperados: {got}")
print(f"OK: client {CLIENT_ID} {action} no realm '{REALM}'; papéis da conta de serviço: {', '.join(got)}")
