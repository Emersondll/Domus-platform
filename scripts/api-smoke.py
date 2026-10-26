#!/usr/bin/env python3
"""Cobertura endpoint x tela e fumaça da API em execução (perfil dev, dados de desenvolvimento).

1. Lê o OpenAPI da API (http://localhost:8081/v3/api-docs) e, para cada operação, procura o último trecho literal do
   caminho no código-fonte do painel e do app do morador. Operações sem tela são listadas (nem todas precisam de tela:
   agente e dispositivos usam a API diretamente).
2. Chama todo GET que só depende do condomínio, com um usuário de cada papel, e falha se algum devolver 5xx.
   Também confirma o isolamento: um token sem vínculo com o condomínio recebe 404 (ou 403), nunca 200.

Uso: scripts/api-smoke.py [--write docs/endpoint-coverage.md]. Não faz push nem altera dados (somente GET).
"""
import glob
import json
import re
import sys
import urllib.error
import urllib.request

API = "http://localhost:8081"
ROLES = {"operator-sol": "OPERATOR", "manager-sol": "CONDO_MANAGER", "resident-sol": "RESIDENT", "alpha-admin": "COMPANY_ADMIN"}


def get(path, subject=None):
    headers = {}
    if subject:
        tok = json.load(urllib.request.urlopen(f"{API}/dev/token?subject={subject}"))["access_token"]
        headers["Authorization"] = f"Bearer {tok}"
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, headers=headers)) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def front_source():
    files = [f for f in glob.glob("web/*/src/*.ts*") if ".test." not in f]
    return "".join(open(f, encoding="utf-8").read() for f in files)


def last_literal(path):
    segs = [s for s in path.split("/") if s and not s.startswith("{")]
    return segs[-1]


def main():
    spec = json.load(urllib.request.urlopen(f"{API}/v3/api-docs"))
    src = front_source()
    rows, no_ui = [], []
    for path, ops in sorted(spec["paths"].items()):
        for method in ops:
            key = last_literal(path)
            used = re.search(r'["`/]' + re.escape(key) + r'(["`?/]|\b)', src) is not None
            who = "agente/dispositivo/mídia" if "/agent/" in path or "/internal/media/" in path or "/device" in path and "events" in path else (
                "morador" if "/me/" in path else ("portaria/painel" if "/operator/" in path else "condomínio"))
            rows.append((method.upper(), path, who, used))
            if not used:
                no_ui.append((method.upper(), path))
    print(f"{len(rows)} endpoints; {len(no_ui)} sem uso aparente nas telas:")
    for m, p in no_ui:
        print(f"  {m:6} {p}")

    # condomínio de desenvolvimento: o primeiro do painel do operador
    status, raw = get("/api/v1/operator/overview", "operator-sol")
    assert status == 200, f"overview: {status}"
    condo = json.loads(raw)[0]["condominiumId"]
    failures = 0
    gets = [p for p, ops in spec["paths"].items() if "get" in ops and re.fullmatch(r"/api/v1/condominiums/\{condominiumId\}(/[^{}]*)?", p)]
    print(f"\nFumaça: {len(gets)} GETs de condomínio x {len(ROLES)} papéis")
    for path in gets:
        url = path.replace("{condominiumId}", condo)
        if url.endswith(".csv"):
            url += "?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z"
        for subject, role in ROLES.items():
            code, _ = get(url, subject)
            if code >= 500:
                failures += 1
                print(f"  FALHA 5xx {code} {role} GET {url}")
        # isolamento: usuário de outra empresa nunca enxerga o condomínio
        code, _ = get(url, "beta-admin")
        if code == 200:
            failures += 1
            print(f"  FALHA vazamento: beta-admin recebeu 200 em GET {url}")
        code, _ = get(url)
        if code not in (401, 403):
            failures += 1
            print(f"  FALHA sem token deveria ser 401/403, veio {code} em GET {url}")
    print("FUMAÇA:", "OK" if failures == 0 else f"{failures} falha(s)")

    if "--write" in sys.argv:
        out = sys.argv[sys.argv.index("--write") + 1]
        with open(out, "w", encoding="utf-8") as f:
            f.write("# Cobertura endpoint × tela\n\nGerado por `scripts/api-smoke.py --write` a partir do OpenAPI da API em execução. "
                    "\"Sem tela\" não é defeito por si: agente local e dispositivos falam direto com a API.\n\n"
                    "| Método | Caminho | Quem usa | Tem tela |\n|---|---|---|---|\n")
            for m, p, who, used in rows:
                f.write(f"| {m} | `{p}` | {who} | {'sim' if used else '**não**'} |\n")
    sys.exit(1 if failures else 0)


main()
