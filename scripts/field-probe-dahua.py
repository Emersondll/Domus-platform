#!/usr/bin/env python3
"""Roteiro de campo para uma controladora Dahua/Intelbras (somente stdlib). Roda do notebook de quem está no local, na mesma rede do equipamento.

Por padrão SÓ LÊ: identifica o equipamento e escuta os eventos por alguns minutos enquanto você passa um cartão / usa a leitora. Abrir a porta é
opcional (--open-door) e pede que você digite ABRIR, com a porta supervisionada. Nada é enviado à central: o resultado vai para uma pasta local.

  DAHUA_PASSWORD=... python3 scripts/field-probe-dahua.py --host 192.168.1.50 --user admin [--seconds 120] [--open-door] [--out ./dahua-probe-out]

Saída (para anexar ao teste de campo; já sem dados pessoais):
  report.md              o que respondeu, quais Code= apareceram, quais campos vêm no data={...} e se o identificador do teste voltou no evento
  events.sanitized.txt   as linhas dos eventos com todo valor trocado por <redigido>, exceto uma lista curta de campos não pessoais
Isso confirma ou derruba as suposições B6/MA1 de docs/reference-analysis/DAHUA_SOURCES.md. A senha nunca é gravada.
"""
import argparse, json, os, re, secrets, sys, time, urllib.error, urllib.parse, urllib.request

# valores destes campos não identificam pessoa: ficam visíveis (ajudam a mapear o evento); todo o resto vira <redigido>
KEEP_VALUES = {"Status", "Type", "Method", "Door", "ReaderID", "Direction", "ErrorCode", "Index", "Channel", "Code", "action", "index", "Count"}
# nomes de campo cujo VALOR nunca é mantido, mesmo que apareça na lista acima por engano
ALWAYS_REDACT = re.compile(r"card|user|name|phone|cpf|rg|mail|pass|pwd|token|face|photo|image|plate|serial|mac|ip|addr", re.I)


def opener(host, port, user, password):
    mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    mgr.add_password(None, f"http://{host}:{port}/", user, password)
    return urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(mgr))


def get(op, url, timeout=8):
    try:
        with op.open(url, timeout=timeout) as r:
            return r.status, r.read(65536).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read(2048).decode("utf-8", "replace")
    except Exception as e:  # rede, tempo esgotado...
        return 0, f"{type(e).__name__}: {e}"


def redact_value(key, value, echo_token):
    if echo_token and echo_token in str(value):
        return "<identificador do teste>"
    if key in KEEP_VALUES and not ALWAYS_REDACT.search(key):
        return str(value)
    return "<redigido>"


def sanitize_data(obj, echo_token, path=""):
    """Troca os valores por <redigido> (menos os não pessoais), mantendo os NOMES dos campos."""
    if isinstance(obj, dict):
        return {k: sanitize_data(v, echo_token, k) for k, v in obj.items()}
    if isinstance(obj, list):
        return [sanitize_data(v, echo_token, path) for v in obj]
    return redact_value(path, obj, echo_token)


def parse_event(line):
    """Linha `Code=X;action=Y;index=N[;data={json}]` -> (code, action, fields, data|None)."""
    m = re.match(r"\s*Code=([^;]+);action=([^;]*)(?:;index=(\d+))?(?:;data=(\{.*\}))?\s*$", line)
    if not m:
        return None
    data = None
    if m.group(4):
        try:
            data = json.loads(m.group(4))
        except ValueError:
            data = None
    return m.group(1), m.group(2), m.group(3), data


def find_echo(data, token, path=""):
    """Devolve o caminho do campo cujo valor contém o identificador do teste (ou None)."""
    if isinstance(data, dict):
        for k, v in data.items():
            hit = find_echo(v, token, f"{path}.{k}" if path else k)
            if hit:
                return hit
    elif isinstance(data, list):
        for i, v in enumerate(data):
            hit = find_echo(v, token, f"{path}[{i}]")
            if hit:
                return hit
    elif token in str(data):
        return path
    return None


def capture(op, base, seconds, token, label):
    """Escuta o fluxo de eventos por `seconds`. Devolve (linhas_sanitizadas, resumo_por_codigo, caminho_do_eco, erro)."""
    url = f"{base}/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5"
    lines, summary, echo, err = [], {}, None, None
    deadline = time.time() + seconds
    try:
        r = op.open(url, timeout=8)
        print(f"  [{label}] escutando eventos por {seconds}s (use a leitora / passe um cartão)...", flush=True)
        while time.time() < deadline:
            try:
                raw = r.readline()
            except Exception:  # tempo de leitura esgotado sem dados: continua até o prazo
                continue
            if not raw:
                break
            line = raw.decode("utf-8", "replace").strip()
            ev = parse_event(line)
            if not ev:
                continue
            code, action, index, data = ev
            entry = summary.setdefault(code, {"count": 0, "fields": set()})
            entry["count"] += 1
            if isinstance(data, dict):
                entry["fields"].update(data.keys())
            if token and data is not None and not echo:
                echo = find_echo(data, token)
            clean = sanitize_data(data, token) if data is not None else None
            lines.append(f"Code={code};action={action}" + (f";index={index}" if index else "")
                         + (";data=" + json.dumps(clean, ensure_ascii=False) if clean is not None else ""))
        r.close()
    except Exception as e:
        err = f"{type(e).__name__}: {e}"
    return lines, summary, echo, err


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=80)
    ap.add_argument("--user", required=True)
    ap.add_argument("--channel", type=int, default=1)
    ap.add_argument("--seconds", type=int, default=120, help="quanto escutar eventos antes de abrir a porta (padrão 120)")
    ap.add_argument("--open-door", action="store_true", help="também testa openDoor (pede confirmação; porta supervisionada)")
    ap.add_argument("--yes-open", action="store_true", help=argparse.SUPPRESS)  # só para o teste automatizado
    ap.add_argument("--out", default="./dahua-probe-out")
    a = ap.parse_args()
    password = os.environ.get("DAHUA_PASSWORD")
    if not password:
        sys.exit("defina a senha em DAHUA_PASSWORD (assim ela não fica no histórico do terminal)")
    base = f"http://{a.host}:{a.port}"
    op = opener(a.host, a.port, a.user, password)
    os.makedirs(a.out, exist_ok=True)
    report = [f"# Teste de campo Dahua/Intelbras — {time.strftime('%Y-%m-%d %H:%M')}", f"Equipamento: {a.host}:{a.port} (usuário informado: {a.user})", ""]

    print("== 1. identificação (somente leitura)")
    report.append("## 1. Identificação")
    ok_auth = False
    for name, path in (("getSystemInfo", "/cgi-bin/magicBox.cgi?action=getSystemInfo"),
                       ("getSoftwareVersion", "/cgi-bin/magicBox.cgi?action=getSoftwareVersion"),
                       ("getDeviceType", "/cgi-bin/magicBox.cgi?action=getDeviceType")):
        status, body = get(op, base + path)
        ok_auth |= status == 200
        shown = "\n".join(l for l in body.splitlines() if not re.search(r"serial|mac|ip", l, re.I))[:600]
        print(f"  {name}: HTTP {status}")
        report.append(f"- `{name}`: HTTP **{status}**" + (f"\n```\n{shown}\n```" if status == 200 and shown else ""))
    if not ok_auth:
        report.append("\n**Nenhuma resposta 200: confira IP, porta, usuário/senha e se a API HTTP está habilitada no equipamento.**")
        open(os.path.join(a.out, "report.md"), "w").write("\n".join(report) + "\n")
        sys.exit("o equipamento não respondeu 200 a nenhuma leitura; veja " + os.path.join(a.out, "report.md"))
    report.append("")

    print("== 2. eventos (somente leitura)")
    lines, summary, _, err = capture(op, base, a.seconds, None, "leitura")
    report.append("## 2. Eventos observados (sem abrir a porta pelo sistema)")
    report.append(f"- Fluxo `eventManager.cgi?action=attach`: {'**falhou** — ' + err if err else 'abriu e entregou eventos' if lines else 'abriu, mas **nenhum evento chegou** no prazo'}")
    for code, e in sorted(summary.items()):
        report.append(f"- `Code={code}`: {e['count']} ocorrência(s); campos no `data`: {', '.join(sorted(e['fields'])) or '(sem data)'}")
    access = [c for c in summary if "access" in c.lower() or "door" in c.lower() or "card" in c.lower()]
    report.append(f"- Eventos relacionados a acesso/porta/cartão: {', '.join(access) if access else '**nenhum — passe um cartão durante a escuta**'}")
    report.append("")

    token_lines = []
    if a.open_door:
        print("== 3. abrir a porta (confirmação)")
        token = "portaria-field-" + secrets.token_hex(4)
        if not a.yes_open:
            if input("A porta vai ABRIR (canal %d). Está junto dela e ela está supervisionada? Digite ABRIR para continuar: " % a.channel).strip() != "ABRIR":
                sys.exit("cancelado; nada foi enviado")
        status, body = get(op, f"{base}/cgi-bin/accessControl.cgi?action=openDoor&channel={a.channel}&UserID={token}&Type=Remote")
        print(f"  openDoor: HTTP {status} corpo={body.strip()[:40]!r}")
        report.append("## 3. Abertura remota")
        report.append(f"- `openDoor` com `UserID={token}`: HTTP **{status}**, corpo `{body.strip()[:60]}` (a suposição é HTTP 200 e corpo `OK`)")
        lines2, summary2, echo, err2 = capture(op, base, 20, token, "após openDoor")
        token_lines = lines2
        report.append("- Eventos nos 20 s seguintes: " + (", ".join(f"`{c}`×{e['count']}" for c, e in sorted(summary2.items())) or "**nenhum**"))
        if echo:
            report.append(f"- **O identificador do teste voltou no evento, no campo `{echo}`.** A suposição (B6) está confirmada para este modelo; o mapeador precisa ler exatamente este campo.")
        else:
            report.append("- **O identificador do teste NÃO voltou em nenhum evento.** Pelo desenho, a central NÃO confirmaria a abertura (ficaria `UNKNOWN`): "
                          "este modelo/firmware exige outro caminho (ver `docs/reference-analysis/DAHUA_SOURCES.md`, plano B).")
        report.append("- Confira a olho: a porta abriu? (anote aqui)")
    else:
        report.append("## 3. Abertura remota\n- Não testada (rode de novo com `--open-door` com a porta supervisionada).")

    with open(os.path.join(a.out, "events.sanitized.txt"), "w") as f:
        f.write("\n".join(lines + token_lines) + "\n")
    report.append(f"\nEventos sanitizados: `events.sanitized.txt` ({len(lines) + len(token_lines)} linhas). Valores pessoais foram trocados por `<redigido>`; **confira o arquivo antes de compartilhar**.")
    with open(os.path.join(a.out, "report.md"), "w") as f:
        f.write("\n".join(report) + "\n")
    print("\nRelatório:", os.path.join(a.out, "report.md"))


if __name__ == "__main__":
    main()
