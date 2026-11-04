#!/usr/bin/env python3
"""Lista os CNPJs cadastrados que a validação `REQUIRE_VALID_CNPJ` recusaria (somente leitura, somente stdlib).

Rode ANTES de ligar a exigência numa instalação que já tem cadastros. Não altera nada: só mostra empresa/condomínio, o valor
guardado e o motivo. Mesmas variáveis do backup.sh: COMPOSE_FILES, PGSERVICE, PGDB, PGUSER.
Uso: python3 scripts/check-tax-ids.py        (sai com 0 se tudo é válido ou vazio; 1 se há CNPJ a corrigir)
"""
import os, re, shlex, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIRST = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
SECOND = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]


def check(d, weights):
    rest = sum(int(c) * w for c, w in zip(d, weights)) % 11
    return 0 if rest < 2 else 11 - rest


def problem(raw):
    """Mesma regra de Cnpj.java: tira . - / e espaços; 14 dígitos, não repetidos, verificadores corretos."""
    d = re.sub(r"[.\-/\s]", "", raw)
    if not re.fullmatch(r"\d{14}", d):
        return "não tem 14 dígitos numéricos"
    if len(set(d)) == 1:
        return "sequência repetida"
    if check(d, FIRST) != int(d[12]) or check(d, SECOND) != int(d[13]):
        return "dígitos verificadores não conferem"
    return None


def query(sql):
    cmd = ["docker", "compose", *shlex.split(os.environ.get("COMPOSE_FILES", "-f docker-compose.yml")), "exec", "-T",
           os.environ.get("PGSERVICE", "postgres"), "psql", "-U", os.environ.get("PGUSER", "portaria"),
           "-d", os.environ.get("PGDB", "portaria"), "-At", "-F", "\t", "-c", sql]
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr.strip(), file=sys.stderr)
        sys.exit(2)
    return [line.split("\t") for line in r.stdout.splitlines() if line]


rows = ([("empresa", n, t) for n, t in query("select name, tax_id from operator_company order by name")]
        + [("condomínio", n, t) for n, t in query("select name, tax_id from condominium where tax_id is not null order by name")])
bad = [(kind, name, tax, problem(tax)) for kind, name, tax in rows if problem(tax)]
print(f"{len(rows)} CNPJ(s) conferidos; {len(bad)} seriam recusados.")
for kind, name, tax, why in bad:
    print(f"  {kind}: {name} | {tax} | {why}")
sys.exit(1 if bad else 0)
