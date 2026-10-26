#!/usr/bin/env python3
"""Teste ponta a ponta em NAVEGADOR REAL (Chromium via Playwright) dos fluxos do morador e do operador.

Sobe sozinho: um banco novo no Postgres do compose, a API (perfil dev), o painel e o app do morador (Vite).
Prova pelo navegador: chamada do morador -> operador atende -> abre a porta (CONFIRMED só com evento do dispositivo) ->
porta sem confirmação termina "estado desconhecido" (aceito != aberto) -> encerramento; convite com QR, veículo, perfil;
e que nenhum token fica em localStorage/sessionStorage.
O login aqui é o de desenvolvimento; o fluxo OIDC/PKCE contra o Keycloak real é provado por scripts/verify_keycloak.py.
Requisitos: `pip install playwright && playwright install chromium`, Postgres em 5433 (docker compose up -d postgres),
jar do backend (mvn -f backend/pom.xml package -DskipTests), Node >= 22.12 e `npm install` em web/.
"""
import glob, json, os, re, secrets, shutil, signal, subprocess, sys, time, urllib.request

from playwright.sync_api import expect, sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)  # o caminho do projeto pode ter espaços: usamos caminhos relativos
SUFFIX = secrets.token_hex(3)
DB = f"e2e_browser_{SUFFIX}"
procs: list[subprocess.Popen] = []
failed = False


def check(name, ok, detail=""):
    global failed
    print(f"  {'ok  ' if ok else 'FAIL'} {name} {detail}")
    failed |= not ok


def node_path():
    """Diretório do Node >= 22 (nvm), se o do sistema for mais antigo."""
    best = None
    for d in glob.glob(os.path.expanduser("~/.nvm/versions/node/v*/bin")):
        m = re.search(r"v(\d+)\.(\d+)", d)
        if m and (int(m[1]), int(m[2])) >= (22, 12):
            best = d
    return best


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


def api(method, path, subject, body=None):
    """Chamada direta à API com o token de desenvolvimento do `subject` (apoio de preparação do cenário)."""
    token = json.load(urllib.request.urlopen(f"http://localhost:8081/dev/token?subject={subject}"))["access_token"]
    req = urllib.request.Request(f"http://localhost:8081{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def take_next(pan):
    """Atende a próxima chamada; repete o clique enquanto a chamada do morador ainda não chegou à fila (evita corrida)."""
    for _ in range(15):
        pan.get_by_role("button", name="Atender próxima").click()
        try:
            expect(pan.get_by_role("button", name="Encerrar atendimento")).to_be_visible(timeout=2000)
            return
        except AssertionError:
            continue
    raise AssertionError("nenhuma chamada chegou à fila")


def more(page, name):
    """Itens do app do morador que ficam em "Mais" (a barra inferior só traz o uso diário)."""
    page.get_by_role("button", name="Mais", exact=True).click()
    page.get_by_role("button", name=name).click()


def cleanup():
    for p in procs:
        try:
            os.killpg(p.pid, signal.SIGTERM)
        except Exception:
            pass
    time.sleep(2)
    # o npm cria netos que podem escapar do grupo: derruba quem ainda escuta nas portas do Vite
    if shutil.which("fuser"):
        subprocess.run(["fuser", "-k", "5173/tcp", "5174/tcp"], capture_output=True)
    psql(f"DROP DATABASE IF EXISTS {DB} WITH (FORCE)")


jar = "backend/target/platform-0.1.0-SNAPSHOT.jar"
if not os.path.exists(jar):
    raise SystemExit("jar não encontrado: mvn -f backend/pom.xml package -DskipTests")
assert psql(f"CREATE DATABASE {DB}").returncode == 0, "Postgres do compose não está no ar"
env = dict(os.environ, DB_URL=f"jdbc:postgresql://localhost:5433/{DB}", DB_USER="portaria", DB_PASSWORD="portaria_dev_only",
           SPRING_PROFILES_ACTIVE="dev")
web_env = dict(os.environ)
if node_path():
    web_env["PATH"] = node_path() + os.pathsep + web_env["PATH"]
logdir = os.environ.get("TMPDIR", "/tmp")
try:
    procs.append(subprocess.Popen(["java", "-jar", jar], env=env, stdout=open(f"{logdir}/e2e-api.log", "w"),
                                  stderr=subprocess.STDOUT, start_new_session=True))
    for ws in ("panel", "resident"):
        procs.append(subprocess.Popen(["npm", "run", "dev", "-w", ws, "--", "--strictPort"], cwd="web", env=web_env,
                                      stdout=open(f"{logdir}/e2e-{ws}.log", "w"), stderr=subprocess.STDOUT, start_new_session=True))
    wait_http("http://localhost:8082/actuator/health")
    wait_http("http://localhost:5173")
    wait_http("http://localhost:5174")

    with sync_playwright() as p:
        browser = p.chromium.launch()
        errors: list[str] = []

        def watch(page, label):
            page.on("pageerror", lambda e: errors.append(f"{label}: {e}"))
            page.on("console", lambda m: errors.append(f"{label}: {m.text}") if m.type == "error" and "401" not in m.text else None)

        print("== app do morador")
        res = browser.new_context(viewport={"width": 390, "height": 900}, locale="pt-BR").new_page()
        watch(res, "morador")
        res.goto("http://localhost:5174")
        res.get_by_role("button", name="Entrar como morador de teste").click()
        expect(res.get_by_role("heading", name="Início")).to_be_visible()
        res.get_by_role("button", name="Chamar a central").click()
        expect(res.get_by_text("Aguardando um operador da central")).to_be_visible()
        check("morador chama a central e vê 'aguardando'", True)
        expect(res.get_by_role("button", name="Chamar a central")).to_be_disabled()
        check("não dá para abrir uma segunda chamada enquanto uma está em andamento", True)
        more(res, "Convites")
        res.get_by_label("Nome do visitante").fill("Visitante E2E")
        res.get_by_role("button", name="Gerar convite").click()
        expect(res.get_by_alt_text("QR Code do convite")).to_be_visible()
        check("convite gera QR Code", True)
        more(res, "Veículos")
        res.get_by_label("Placa").fill("E2E1A23")
        res.get_by_role("button", name="Cadastrar veículo").click()
        expect(res.get_by_text("E2E1A23")).to_be_visible()
        check("veículo cadastrado aparece na lista", True)
        more(res, "Perfil")
        res.get_by_label("Telefone").fill("+55 11 98888-0000")
        res.get_by_role("button", name="Salvar contato").click()
        expect(res.get_by_text("Contato atualizado.")).to_be_visible()
        res.reload()
        res.get_by_role("button", name="Entrar como morador de teste").click()
        more(res, "Perfil")
        expect(res.get_by_label("Telefone")).to_have_value("+55 11 98888-0000")
        check("contato salvo persiste após recarregar", True)
        tokens = res.evaluate("() => JSON.stringify([localStorage, sessionStorage])")
        check("nenhum token guardado em localStorage/sessionStorage (morador)", "eyJ" not in tokens)

        print("== painel da central")
        pan = browser.new_context(viewport={"width": 1280, "height": 900}, locale="pt-BR").new_page()
        watch(pan, "painel")
        pan.goto("http://localhost:5173")
        pan.get_by_role("button", name="Entrar").click()
        pan.get_by_role("button", name="Iniciar turno").click()
        expect(pan.get_by_text("Encerrar turno")).to_be_visible()
        take_next(pan)
        expect(pan.get_by_role("heading", name="Abrir porta")).to_be_visible()
        check("operador atende a chamada do morador", True)

        options = pan.locator("#door-device option").all_text_contents()
        main = next(o for o in options if "Portão principal" in o)
        pan.locator("#door-device").select_option(label=main)
        pan.get_by_label("Justificativa da abertura").fill("Morador confirmou por telefone")
        pan.get_by_role("button", name="Abrir porta").click()
        expect(pan.get_by_text("Porta aberta — confirmado")).to_be_visible(timeout=15000)
        check("porta com confirmação do dispositivo -> 'Porta aberta — confirmado'", True)

        # o morador acompanha: atendida -> encerrada, com o desfecho (a tela atualiza sozinha a cada 5 s)
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_text("Em atendimento pela central")).to_be_visible(timeout=15000)
        check("morador vê que a chamada está em atendimento", True)
        pan.get_by_role("button", name="Encerrar atendimento").click()
        expect(pan.get_by_text("Nenhum atendimento em andamento.")).to_be_visible()
        expect(res.get_by_text("Atendimento concluído.").first).to_be_visible(timeout=15000)
        check("morador recebe o desfecho quando a portaria encerra", True)
        more(res, "Convites")
        res.get_by_role("button", name="Visitas").click()
        expect(res.get_by_role("heading", name="Visitas")).to_be_visible()
        res.get_by_role("button", name="Encomendas").click()
        expect(res.get_by_role("heading", name="Encomendas")).to_be_visible()
        check("abas Visitas e Encomendas abrem", True)

        # segunda chamada, porta que aceita o comando mas nunca confirma
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_role("button", name="Chamar a central")).to_be_enabled()
        res.get_by_role("button", name="Chamar a central").click()
        take_next(pan)
        # espera o atendimento anterior sair da tela (a bancada nova nasce sem comando e sem dispositivo escolhido)
        expect(pan.get_by_text("Porta aberta — confirmado")).to_have_count(0)
        expect(pan.locator("#door-device option", has_text="garagem")).to_have_count(1, timeout=20000)
        options = pan.locator("#door-device option").all_text_contents()
        garage = next(o for o in options if "garagem" in o)
        pan.locator("#door-device").select_option(label=garage)
        pan.get_by_label("Justificativa da abertura").fill("Teste de porta sem confirmação")
        pan.get_by_role("button", name="Abrir porta").click()
        expect(pan.get_by_text("Aguardando confirmação do dispositivo")).to_be_visible(timeout=10000)
        expect(pan.get_by_text("Sem confirmação — estado desconhecido")).to_be_visible(timeout=45000)
        check("porta sem evento NÃO é dada como aberta ('estado desconhecido')", True)
        check("a tela nunca mostrou 'Porta aberta' para a porta sem confirmação",
              pan.get_by_text("Porta aberta — confirmado").count() == 0)

        pan.get_by_role("button", name="Encerrar atendimento").click()
        expect(pan.get_by_text("Nenhum atendimento em andamento.")).to_be_visible()
        check("operador encerra o segundo atendimento (a lista fica vazia)", True)

        # autorização de visitante pelo celular
        expect(res.get_by_role("button", name="Chamar a central")).to_be_enabled(timeout=15000)
        res.get_by_role("button", name="Chamar a central").click()
        take_next(pan)
        pan.get_by_label("Nome do visitante").fill("Tia E2E")
        pan.get_by_role("button", name="Pedir autorização ao morador").click()
        expect(res.get_by_text("Tia E2E").first).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Autorizar").click()
        expect(pan.get_by_text("AUTORIZADO pelo morador")).to_be_visible(timeout=15000)
        check("morador autoriza o visitante pelo app e a portaria vê 'AUTORIZADO'", True)
        expect(pan.get_by_label("Justificativa da abertura")).to_have_value(re.compile("Morador autorizou pelo app"))
        check("a justificativa da abertura vem preenchida, mas a porta não abre sozinha",
              pan.get_by_text("Porta aberta — confirmado").count() == 0)
        pan.get_by_role("button", name="Encerrar atendimento").click()
        expect(pan.get_by_text("Nenhum atendimento em andamento.")).to_be_visible()

        # alerta de pânico: confirmação, prioridade e retorno ao morador
        expect(res.get_by_role("button", name="Alerta de pânico")).to_be_enabled(timeout=15000)
        res.get_by_role("button", name="Alerta de pânico").click()
        res.get_by_role("button", name="Confirmar pânico").click()
        expect(res.get_by_text("Seu alerta de pânico")).to_be_visible()
        dialog = pan.get_by_role("alertdialog", name="Alerta de pânico")
        expect(dialog).to_be_visible(timeout=15000)
        expect(dialog).to_contain_text("A / 101")
        check("pânico abre um alerta em tela cheia na central, com a unidade de origem", True)
        pan.get_by_role("button", name="Silenciar som").click()
        expect(dialog).to_be_visible()  # silenciar não dispensa o alerta
        dialog.get_by_role("button", name="Atender agora").click()
        expect(dialog).to_have_count(0, timeout=15000)
        expect(pan.get_by_text("Unidade: A / 101")).to_be_visible()
        expect(pan.get_by_text("PÂNICO", exact=True).first).to_be_visible()
        check("o operador atende o pânico pela janela de alerta", True)
        expect(res.get_by_text("Em atendimento pela central")).to_be_visible(timeout=15000)
        check("o morador vê que o pânico foi atendido", True)
        pan.get_by_role("button", name="Encerrar atendimento").click()
        expect(pan.get_by_text("Nenhum atendimento em andamento.")).to_be_visible()

        # mesa do condomínio: encomendas, visitantes, portas e câmeras
        pan.get_by_role("button", name=re.compile("Residencial Sol")).click()
        pan.get_by_label("Unidade").select_option(index=1)
        pan.get_by_label("Transportadora").fill("Correios")
        pan.get_by_role("button", name="Registrar encomenda").click()
        expect(pan.get_by_text("Aguardando retirada (1)")).to_be_visible()
        check("operador registra uma encomenda", True)
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_role("button", name="Encomendas a retirar")).to_contain_text("1", timeout=15000)
        check("morador vê 1 encomenda a retirar no início", True)
        pan.get_by_role("button", name="Entregar").click()
        pan.get_by_label("Quem retirou").fill("Morador E2E")
        pan.get_by_role("button", name="Confirmar entrega").click()
        expect(pan.get_by_text("Nenhuma encomenda aguardando.")).to_be_visible()
        expect(res.get_by_role("button", name="Encomendas a retirar")).to_contain_text("0", timeout=15000)
        check("entrega registrada e o morador deixa de ter encomenda pendente", True)
        pan.get_by_role("tab", name="Portas").click()
        expect(pan.get_by_text("Portão principal").first).to_be_visible()
        pan.get_by_role("tab", name="Câmeras").click()
        expect(pan.get_by_role("heading", name="Cadastrar câmera")).to_be_visible()
        check("mesa do condomínio: portas e câmeras", True)

        # Lote B: comunicados, livro de turno, saída de visitante, relatório/CSV e abertura remota
        condo = api("GET", "/api/v1/me/residencies", "resident-sol")[0]["condominiumId"]
        base = f"/api/v1/condominiums/{condo}"
        pan.get_by_role("tab", name="Comunicados").click()
        pan.get_by_label("Título").fill("Manutenção E2E")
        pan.get_by_label("Mensagem").fill("Caixa d'água na sexta às 9h")
        pan.get_by_role("button", name="Publicar para todos os moradores").click()
        expect(pan.get_by_text("Comunicado publicado.")).to_be_visible()
        more(res, "Avisos")
        expect(res.get_by_text("Manutenção E2E").first).to_be_visible(timeout=20000)
        check("comunicado publicado na portaria aparece em Avisos do morador", True)

        # mensagens individuais: morador escreve, a portaria responde, o morador lê a resposta
        res.get_by_role("button", name="Mensagens").click()
        res.get_by_label("Nova mensagem").fill("Vou receber um prestador amanhã")
        res.get_by_role("button", name="Enviar").click()
        expect(res.get_by_text("Vou receber um prestador amanhã")).to_be_visible()
        pan.get_by_role("tab", name="Mensagens").click()
        expect(pan.get_by_text(re.compile("nova"))).to_be_visible(timeout=15000)
        pan.get_by_role("button", name=re.compile("Morador")).first.click()
        expect(pan.get_by_text("Vou receber um prestador amanhã")).to_be_visible()
        pan.get_by_label("Resposta").fill("Combinado, avise o nome na portaria")
        pan.get_by_role("button", name="Responder").click()
        expect(res.get_by_text("Combinado, avise o nome na portaria")).to_be_visible(timeout=20000)
        check("conversa morador <-> portaria nos dois sentidos", True)

        # cadastros do domicílio: o morador cadastra cônjuge e animal; a portaria enxerga nos cadastros
        more(res, "Minha casa")
        res.get_by_label("Nome completo").fill("Conjuge E2E")
        res.get_by_label("Vínculo").select_option("SPOUSE")
        res.get_by_role("button", name="Adicionar pessoa").click()
        expect(res.get_by_text("Conjuge E2E")).to_be_visible()
        res.get_by_label("Nome do animal").fill("Rex E2E")
        res.get_by_role("button", name="Adicionar animal").click()
        expect(res.get_by_text("Rex E2E")).to_be_visible()
        pan.get_by_role("tab", name="Cadastros").click()
        pan.get_by_role("button", name="Moradores").click()
        pan.get_by_label("Unidade", exact=True).select_option(label="A / 101")
        expect(pan.get_by_text("Conjuge E2E")).to_be_visible(timeout=15000)
        pan.get_by_role("button", name="Animais").click()
        expect(pan.get_by_text(re.compile("Rex E2E"))).to_be_visible(timeout=15000)
        check("morador cadastra cônjuge e animal no app e a portaria os vê em Cadastros", True)
        res.get_by_role("button", name="Início", exact=True).click()

        # câmeras: o síndico cadastra (API de apoio); sem RTSP não há botão de vídeo, e nada é simulado
        api("POST", f"{base}/devices", "manager-sol", {"name": "Câmera hall E2E", "kind": "CAMERA", "adapter": "SIMULATOR",
                                                       "entrance": "portaria", "config": {}})
        pan.get_by_role("tab", name="Câmeras").click()
        expect(pan.get_by_text("Câmera hall E2E")).to_be_visible(timeout=15000)
        expect(pan.get_by_text("sem vídeo configurado").first).to_be_visible()
        check("câmera sem RTSP aparece na aba Câmeras sem botão de vídeo", True)

        # vídeo ao vivo: com MediaMTX + fonte de teste (E2E_LIVE_VIDEO=1, ver docs/cameras.md) o player precisa chegar a "Ao vivo";
        # sem eles, o painel precisa mostrar o erro (nunca imagem falsa)
        api("POST", f"{base}/devices", "manager-sol", {"name": "Câmera RTSP E2E", "kind": "CAMERA", "adapter": "ONVIF",
                                                       "entrance": "garagem", "config": {"rtspUrl": "rtsp://127.0.0.1:8554/fake-src"}})
        pan.get_by_role("tab", name="Portas").click()
        pan.get_by_role("tab", name="Câmeras").click()
        pan.get_by_role("button", name="Ver ao vivo Câmera RTSP E2E").click()
        if os.environ.get("E2E_LIVE_VIDEO") == "1":
            expect(pan.get_by_text("Ao vivo · Câmera RTSP E2E")).to_be_visible(timeout=30000)
            check("vídeo ao vivo: o player conecta ao HLS do servidor de mídia (token do backend)", True)
        else:
            expect(pan.get_by_role("alert").filter(has_text=re.compile("servidor de mídia|Sem imagem|indispon", re.I))).to_be_visible(timeout=30000)
            check("vídeo ao vivo sem servidor de mídia: erro explícito, sem imagem simulada", True)
        pan.get_by_role("button", name="Fechar vídeo").click()

        pan.get_by_role("tab", name="Livro de turno").click()
        pan.get_by_label("Texto").fill("Ronda das 22h concluída sem ocorrências")
        pan.get_by_role("button", name="Registrar", exact=True).click()
        expect(pan.get_by_text("Ronda das 22h concluída sem ocorrências")).to_be_visible()
        check("livro de turno: registro aparece no histórico", True)

        inv = api("POST", f"{base}/me/invitations", "resident-sol", {
            "visitorName": "Visitante Saida E2E", "kind": "VISITOR", "maxUses": 1,
            "validFrom": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "validUntil": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3600))})
        pan.get_by_role("tab", name="Visitantes").click()
        pan.get_by_label("Código do convite").fill(inv["token"])
        pan.get_by_role("button", name="Validar", exact=True).click()
        expect(pan.get_by_text(re.compile("Entrada liberada: Visitante Saida E2E"))).to_be_visible()
        check("portaria valida o convite pela tela (e a tela diz que a porta não abre)",
              pan.get_by_text(re.compile("a porta não é aberta")).count() == 1)
        pan.get_by_role("tab", name="Visitantes").click()
        expect(pan.get_by_text(re.compile("Visitante Saida E2E · unidade"))).to_be_visible(timeout=15000)
        row = pan.get_by_role("listitem").filter(has_text="Visitante Saida E2E")
        row.get_by_role("button", name="Registrar saída").click()
        expect(pan.get_by_text(re.compile("Visitante Saida E2E · unidade"))).to_have_count(0, timeout=15000)
        check("visitante no local tem a saída registrada e some da lista", True)

        pan.get_by_role("tab", name="Relatórios").click()
        pan.get_by_role("button", name="Consultar").click()
        expect(pan.get_by_text(re.compile("Visitante Saida E2E")).first).to_be_visible()
        expect(pan.get_by_text(re.compile("saiu"))).to_be_visible()
        with pan.expect_download() as dl:
            pan.get_by_role("button", name="Exportar CSV").click()
        csv_path = dl.value.path()
        content = open(csv_path, encoding="utf-8").read()
        check("relatório mostra a saída e o CSV baixa com o visitante", "Visitante Saida E2E" in content
              and content.startswith("data_hora,entrada"))

        # abertura remota pelo morador: desligada por padrão; síndico liga a política e marca a porta
        more(res, "Convites")
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_role("button", name="Chamar a central")).to_be_visible()
        check("com a política desligada o morador não vê 'Abrir portão'",
              res.get_by_role("heading", name="Abrir portão").count() == 0)
        devices = api("GET", f"{base}/devices?size=50", "manager-sol")["items"]
        door = next(d for d in devices if d["name"] == "Portão principal")
        api("PUT", f"{base}/remote-open", "manager-sol", {"enabled": True})
        api("PUT", f"{base}/devices/{door['id']}/resident-openable", "manager-sol", {"allowed": True})
        more(res, "Convites")
        res.get_by_role("button", name="Início", exact=True).click()
        res.get_by_role("button", name="Abrir Portão principal").click()
        expect(res.get_by_text("Porta aberta (confirmado pelo equipamento).")).to_be_visible(timeout=20000)
        check("morador abre o portão pelo app e só vê 'aberta' com a confirmação do equipamento", True)
        api("PUT", f"{base}/remote-open", "manager-sol", {"enabled": False})
        tokens = pan.evaluate("() => JSON.stringify([localStorage, sessionStorage])")
        check("nenhum token guardado em localStorage/sessionStorage (painel)", "eyJ" not in tokens)
        check("sem erros de JavaScript/console nas duas telas", not errors, str(errors[:3]))
        browser.close()
finally:
    cleanup()
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
