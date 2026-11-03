#!/usr/bin/env bash
# Varredura de vulnerabilidades (HIGH e CRITICAL) das imagens usadas em produção, com o Trivy rodando em contêiner.
# Não monta o socket do Docker: cada imagem é exportada com `docker save` e o Trivy lê o arquivo.
# Uso: scripts/scan-images.sh [diretório-de-saída]   (padrão: ./scan-images-out; guarde o relatório fora do Git)
# Requer Docker e rede (baixa o Trivy e o banco de vulnerabilidades). O jar do backend precisa estar empacotado (mvn package).
# Resultado informativo: o script NÃO falha por achados; leia o resumo e decida (a maioria dos achados vem da imagem-base do fornecedor).
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(realpath -m "${1:-$ROOT/scan-images-out}")"
mkdir -p "$OUT"; chmod 700 "$OUT"
TRIVY="${TRIVY_IMAGE:-aquasec/trivy:latest}"
cd "$ROOT"
docker build -q -t portaria-platform:local backend >/dev/null 2>&1 || echo "AVISO: não foi possível montar a imagem da API (falta o jar?)"
# imagens exatamente como o docker-compose.prod.yml as referencia
mapfile -t IMAGES < <(grep -hE '^\s+image:' deploy/docker-compose.prod.yml | sed -E 's/^\s+image:\s*//' | sort -u)
for ref in "${IMAGES[@]}"; do
  docker image inspect "$ref" >/dev/null 2>&1 || docker pull -q "$ref" >/dev/null 2>&1
  name="$(echo "$ref" | sed 's#[/:@]#_#g' | cut -c1-50)"
  docker save -o "$OUT/$name.tar" "$ref" || { echo "FALHA ao exportar $ref"; continue; }
  docker run --rm -v "$OUT:/scan" -v trivy-cache:/root/.cache "$TRIVY" image --input "/scan/$name.tar" \
    --severity HIGH,CRITICAL --format json --output "/scan/$name.json" --quiet >/dev/null 2>&1
  rm -f "$OUT/$name.tar"
done
python3 - "$OUT" <<'PY'
import collections, glob, json, sys
print(f"{'imagem':44} {'CRIT':>5} {'HIGH':>5} | com correção disponível: CRIT HIGH")
for f in sorted(glob.glob(sys.argv[1] + "/*.json")):
    d = json.load(open(f)); t = collections.Counter(); fx = collections.Counter()
    for r in d.get("Results", []):
        for v in r.get("Vulnerabilities") or []:
            t[v["Severity"]] += 1
            fx[v["Severity"]] += bool(v.get("FixedVersion"))
    print(f"{f.split('/')[-1][:-5]:44} {t['CRITICAL']:>5} {t['HIGH']:>5} | {fx['CRITICAL']:>29} {fx['HIGH']:>4}")
PY
