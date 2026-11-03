#!/usr/bin/env python3
"""Confere a contenção de rede da stack de produção (sem subir nada): só o proxy tem saída.

Lê `docker compose config --format json` e falha se: a rede `internal` deixar de ser interna; banco, Keycloak ou API entrarem em
qualquer rede que não seja a interna; ou outro serviço além do proxy (e, só para publicar portas locais, Prometheus/Grafana) entrar
numa rede com saída. O override opcional de dispositivos é checado à parte: só a API pode ganhar a rede `devices`.
Uso: scripts/check-prod-network.py   (precisa das variáveis obrigatórias; valores fictícios servem, como na CI)
"""
import json, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = dict(os.environ, DB_PASSWORD="x", DOMAIN="example.test", KEYCLOAK_ADMIN="a", KEYCLOAK_ADMIN_PASSWORD="x",
           CREDENTIAL_HMAC_SECRET="x", AGENT_POLICY_MASTER_SECRET="x", GRAFANA_PASSWORD="x",
           FIELD_ENCRYPTION_KEYS="k1:x", BLIND_INDEX_KEY="x", PROVISIONER_CLIENT_SECRET="x")
failed = False


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def config(*extra):
    out = subprocess.run(["docker", "compose", "-f", "deploy/docker-compose.prod.yml", *extra, "--profile", "observability",
                          "config", "--format", "json"], cwd=ROOT, env=ENV, capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def nets(cfg, svc):
    return set((cfg["services"][svc].get("networks") or {}).keys())


base = config()
print("== configuração base")
check("a rede `internal` é interna (sem saída)", base["networks"]["internal"].get("internal") is True)
for svc in ("db", "keycloak", "app"):
    check(f"{svc} só está na rede interna", nets(base, svc) == {"internal"}, str(sorted(nets(base, svc))))
check("o proxy é a única porta de entrada e fala com os de dentro", nets(base, "proxy") == {"edge", "internal"}, str(sorted(nets(base, "proxy"))))
outbound = {n for n, v in base["networks"].items() if not v.get("internal")}
allowed = {"proxy", "prometheus", "grafana"}
leaking = sorted(s for s in base["services"] if nets(base, s) & outbound and s not in allowed)
check("nenhum outro serviço tem rede com saída", not leaking, str(leaking))
check("a API não publica porta no servidor", not base["services"]["app"].get("ports"))
check("o Keycloak não publica porta no servidor", not base["services"]["keycloak"].get("ports"))

print("== override opcional de dispositivos")
dev = config("-f", "deploy/docker-compose.prod.devices.yml")
check("só a API ganha a rede `devices`", [s for s in dev["services"] if "devices" in nets(dev, s)] == ["app"])
check("com o override a API continua na rede interna", "internal" in nets(dev, "app"))
check("o override não muda os demais serviços", all(nets(dev, s) == nets(base, s) for s in base["services"] if s != "app"))

print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
