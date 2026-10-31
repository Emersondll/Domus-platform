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
import glob, json, os, re, secrets, shutil, signal, subprocess, sys, time, urllib.error, urllib.request

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

def open_registry(page):
    """Cadastros é uma seção própria (botão no cabeçalho): abre se ainda não estiver aberta."""
    btn = page.get_by_role("button", name="Cadastros", exact=True)
    if btn.get_attribute("aria-pressed") != "true":
        btn.click()


def close_registry(page):
    """Volta à mesa do condomínio (o botão Cadastros alterna)."""
    btn = page.get_by_role("button", name="Cadastros", exact=True)
    if btn.get_attribute("aria-pressed") == "true":
        btn.click()



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


def synthetic_png(w=240, h=240):
    """PNG sintético (gradiente) sem biblioteca de imagem: nunca se usa foto de pessoa nos testes."""
    import struct, zlib
    raw = b"".join(b"\x00" + bytes(v for x in range(w) for v in (x % 256, y % 256, (x + y) % 256)) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def device_event(device_id, key, body):
    """Evento de um equipamento (chave do dispositivo, sem JWT), como o leitor real enviaria."""
    req = urllib.request.Request(f"http://localhost:8081/api/v1/devices/{device_id}/events", method="POST",
                                 data=json.dumps(body).encode(),
                                 headers={"X-Device-Key": key, "Content-Type": "application/json"})
    return urllib.request.urlopen(req).status


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
           SPRING_PROFILES_ACTIVE="dev",
           # a roteiro faz muitas chamadas seguidas de um só IP; o limite de produção é coberto por RateLimitIT
           PORTARIA_RATE_LIMIT_API_PER_MINUTE="1000000")
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
        failed_requests: list[str] = []  # só para diagnóstico: diz qual URL deu 4xx/5xx quando o console reclama

        traced: set[tuple[str, str, int]] = set()  # E2E_TRACE_REQUESTS=arquivo: quais rotas as telas realmente chamam

        def trace(r):
            if "/api/" in r.url:
                traced.add((r.request.method, r.url.split("?")[0].split("localhost")[-1].split("/", 1)[-1], r.status))

        def watch(page, label):
            if os.environ.get("E2E_TRACE_REQUESTS"):
                page.on("response", trace)
            page.on("pageerror", lambda e: errors.append(f"{label}: {e}"))
            page.on("response", lambda r: failed_requests.append(f"{label}: {r.status} {r.request.method} {r.url}") if r.status >= 400 else None)
            page.on("console", lambda m: errors.append(f"{label}: {m.text}") if m.type == "error" and "401" not in m.text else None)

        print("== app do morador")
        res = browser.new_context(viewport={"width": 390, "height": 900}, locale="pt-BR").new_page()
        watch(res, "morador")
        res.goto("http://localhost:5174")
        res.get_by_role("button", name="Entrar como morador de teste").click()
        expect(res.get_by_role("heading", name="Início", exact=True)).to_be_visible()
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
        expect(res.get_by_label("Meu cadastro")).to_contain_text("ativo", timeout=15000)
        check("o morador vê o próprio cadastro no perfil", True)
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

        pan.locator("#door-device option", has_text="Portão principal").first.wait_for(state="attached", timeout=20000)  # a lista de portas carrega depois
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

        # o morador também pode negar a entrada pelo celular
        expect(res.get_by_role("button", name="Chamar a central")).to_be_enabled(timeout=15000)
        res.get_by_role("button", name="Chamar a central").click()
        take_next(pan)
        pan.get_by_label("Nome do visitante").fill("Visitante Negado E2E")
        pan.get_by_role("button", name="Pedir autorização ao morador").click()
        expect(res.get_by_text("Visitante Negado E2E").first).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Negar").click()
        expect(pan.get_by_text("NEGADO pelo morador")).to_be_visible(timeout=15000)
        check("morador nega a entrada pelo app e a portaria vê 'NEGADO'", True)
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
        pan.get_by_role("tab", name="Encomendas").click()
        pan.get_by_label("Unidade").select_option(index=1)
        pan.get_by_label("Transportadora").fill("Correios")
        pan.get_by_role("button", name="Registrar encomenda").click()
        expect(pan.get_by_text("Aguardando retirada (1)")).to_be_visible()
        check("operador registra uma encomenda", True)
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_role("button", name="Encomendas a retirar")).to_contain_text("1", timeout=15000)
        check("morador vê 1 encomenda a retirar no início", True)
        pan.get_by_role("button", name=re.compile("Lembrar morador da encomenda")).click()
        expect(pan.get_by_text("lembrete enviado")).to_be_visible()
        check("a portaria lembra o morador da encomenda pela tela", True)
        res.get_by_role("button", name="Início", exact=True).click()
        res.get_by_role("button", name="Marcar como lida").first.click(timeout=20000)
        check("o morador marca um aviso como lido", True)
        pan.get_by_role("button", name="Entregar").click()
        pan.get_by_label("Quem retirou").fill("Morador E2E")
        pan.get_by_role("button", name="Confirmar entrega").click()
        expect(pan.get_by_text("Nenhuma encomenda aguardando.")).to_be_visible()
        expect(res.get_by_role("button", name="Encomendas a retirar")).to_contain_text("0", timeout=15000)
        check("entrega registrada e o morador deixa de ter encomenda pendente", True)
        pan.get_by_role("tab", name="Portas").click()
        expect(pan.get_by_text("Portão principal").first).to_be_visible()
        pan.get_by_role("tab", name="Câmeras").click()
        expect(pan.get_by_role("region", name="Câmeras para moradores")).to_be_visible()
        # o painel do e2e é do operador: ele vê as câmeras, mas não o cadastro nem a liberação (só síndico/administração)
        expect(pan.get_by_role("heading", name="Cadastrar câmera")).to_have_count(0)
        check("mesa do condomínio: portas e câmeras (operador não vê cadastro nem liberação)", True)

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
        res.get_by_role("button", name="Início", exact=True).click()
        pan.get_by_label("Resposta").fill("Segunda resposta da portaria")
        pan.get_by_role("button", name="Responder").click()
        seen = False
        for _ in range(12):
            res.get_by_role("button", name="Encomendas", exact=True).click()
            res.get_by_role("button", name="Início", exact=True).click()
            if "não lida" in (res.get_by_role("button", name=re.compile("^Mensagens")).get_attribute("title") or ""):
                seen = True
                break
            time.sleep(1)
        check("o app mostra o contador de mensagens não lidas da portaria", seen)
        res.get_by_role("button", name=re.compile("^Mensagens")).click()
        expect(res.get_by_text("Segunda resposta da portaria")).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Início", exact=True).click()

        # cadastros do domicílio: o morador cadastra cônjuge e animal; a portaria enxerga nos cadastros
        more(res, "Minha casa")
        res.get_by_label("Nome completo").fill("Conjuge E2E")
        res.get_by_label("Vínculo").select_option("SPOUSE")
        res.get_by_role("button", name="Adicionar pessoa").click()
        expect(res.get_by_text("Conjuge E2E")).to_be_visible()
        res.get_by_label("Nome do animal").fill("Rex E2E")
        res.get_by_role("button", name="Adicionar animal").click()
        expect(res.get_by_text("Rex E2E")).to_be_visible()
        res.get_by_role("button", name="Editar Rex E2E").click()
        res.get_by_label("Nome do animal").fill("Rex E2E II")
        res.get_by_role("button", name="Salvar alterações do animal").click()
        expect(res.get_by_text(re.compile("Rex E2E II"))).to_be_visible()
        check("o morador edita o próprio animal pelo app", True)
        open_registry(pan)
        pan.get_by_role("button", name="Moradores").click()
        pan.get_by_label("Unidade", exact=True).select_option(label="A / 101")
        expect(pan.get_by_text("Conjuge E2E")).to_be_visible(timeout=15000)
        pan.get_by_role("button", name=re.compile("^Detalhes de ")).first.click()
        expect(pan.get_by_label("Detalhe do morador")).to_be_visible(timeout=15000)
        pan.get_by_role("button", name="Fechar detalhe").click()
        check("a portaria abre o detalhe do morador pela tela", True)
        pan.get_by_role("button", name="Animais").click()
        expect(pan.get_by_text(re.compile("Rex E2E"))).to_be_visible(timeout=15000)
        check("morador cadastra cônjuge e animal no app e a portaria os vê em Cadastros", True)
        res.get_by_role("button", name="Remover Conjuge E2E").click()
        expect(res.get_by_text("Conjuge E2E")).to_have_count(0)
        res.get_by_role("button", name="Remover Rex E2E II").click()
        expect(res.get_by_text("Rex E2E")).to_have_count(0)
        check("morador remove o familiar e o animal pelo app", True)
        res.get_by_role("button", name="Início", exact=True).click()

        # câmeras: o síndico cadastra (API de apoio); sem RTSP não há botão de vídeo, e nada é simulado
        hall = api("POST", f"{base}/devices", "manager-sol", {"name": "Câmera hall E2E", "kind": "CAMERA", "adapter": "SIMULATOR",
                                                              "entrance": "portaria", "config": {}})
        # a câmera simulada do hall é a câmera de entrada de visitas (imagem sintética, nunca parece real)
        api("POST", f"{base}/devices/{hall['id']}/visitor-entrance", "manager-sol", {"enabled": True})
        close_registry(pan)
        pan.get_by_role("tab", name="Câmeras").click()
        expect(pan.locator("div[aria-label='Câmeras']").get_by_text("Câmera hall E2E")).to_be_visible(timeout=15000)
        expect(pan.get_by_text("sem vídeo configurado").first).to_be_visible()
        check("câmera sem RTSP aparece na aba Câmeras sem botão de vídeo", True)

        # vídeo ao vivo: com MediaMTX + fonte de teste (E2E_LIVE_VIDEO=1, ver docs/cameras.md) o player precisa chegar a "Ao vivo";
        # sem eles, o painel precisa mostrar o erro (nunca imagem falsa)
        rtsp_cam = api("POST", f"{base}/devices", "manager-sol", {"name": "Câmera RTSP E2E", "kind": "CAMERA", "adapter": "ONVIF",
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
        # monitoramento na mesa: a câmera aparece junto das demais informações do condomínio e só abre quando o operador pede
        pan.get_by_role("button", name="Monitorar câmeras ao vivo").click()
        monitor = pan.get_by_role("region", name="Monitoramento de câmeras")
        monitor.get_by_role("button", name="Monitorar Câmera RTSP E2E").click(timeout=40000)
        if os.environ.get("E2E_LIVE_VIDEO") == "1":
            expect(pan.get_by_text("Ao vivo · Câmera RTSP E2E")).to_be_visible(timeout=30000)
            check("a mesa do condomínio monitora as câmeras ao vivo sem sair da tela", True)
        else:
            expect(pan.get_by_role("alert").filter(has_text=re.compile("servidor de mídia|Sem imagem|indispon", re.I))).to_be_visible(timeout=30000)
            check("a mesa do condomínio monitora as câmeras (sem servidor de mídia: erro explícito)", True)
        pan.get_by_role("button", name="Fechar vídeo Câmera RTSP E2E").click()
        # o síndico libera a câmera ao morador (máx. 4); o morador a vê em Mais > Câmeras e só ela
        api("POST", f"{base}/devices/{rtsp_cam['id']}/resident-viewable", "manager-sol", {"enabled": True})
        res.get_by_role("button", name="Mais", exact=True).click()
        res.get_by_role("button", name="Câmeras", exact=True).click()
        expect(res.get_by_text("Câmera hall E2E")).to_have_count(0)
        res.get_by_label(re.compile("as imagens mostram outras pessoas")).check()
        res.get_by_role("button", name="Ver Câmera RTSP E2E").click()
        if os.environ.get("E2E_LIVE_VIDEO") == "1":
            expect(res.get_by_text("Ao vivo · Câmera RTSP E2E")).to_be_visible(timeout=30000)
            check("o morador vê ao vivo a câmera de entrada liberada pelo síndico (e só as liberadas)", True)
        else:
            expect(res.get_by_role("alert").filter(has_text=re.compile("servidor de mídia|Sem imagem|indispon", re.I))).to_be_visible(timeout=30000)
            check("o morador vê a câmera liberada (sem servidor de mídia: erro explícito)", True)
        res.get_by_role("button", name="Fechar vídeo Câmera RTSP E2E").click()
        res.get_by_role("button", name="Ir para o início").click()
        expect(res.get_by_role("heading", name="Início")).to_be_visible()
        check("o botão Início do cabeçalho do morador volta ao início de qualquer tela", True)
        if os.environ.get("E2E_LIVE_VIDEO") == "1":
            # captura de quadro de câmera pelo servidor de mídia + ffmpeg (fonte RTSP de teste): precisa devolver um JPEG
            api("POST", f"{base}/devices/{rtsp_cam['id']}/visitor-entrance", "manager-sol", {"enabled": True})
            token = json.load(urllib.request.urlopen("http://localhost:8081/dev/token?subject=operator-sol"))["access_token"]
            snap_req = urllib.request.Request(f"http://localhost:8081{base}/entrance-snapshots", method="POST",
                                              data=json.dumps({"entrance": "garagem"}).encode(),
                                              headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
            try:
                snap_id = json.load(urllib.request.urlopen(snap_req, timeout=30))["id"]
            except urllib.error.HTTPError as err:
                raise SystemExit(f"captura de quadro recusada: HTTP {err.code} {err.read().decode()[:300]}")
            img_req = urllib.request.Request(f"http://localhost:8081{base}/entrance-snapshots/{snap_id}/image",
                                             headers={"Authorization": f"Bearer {token}"})
            img = urllib.request.urlopen(img_req).read()
            check("captura de quadro pela câmera RTSP (MediaMTX + ffmpeg) devolve um JPEG", img[:2] == b"\xff\xd8" and len(img) > 1000)
            api("POST", f"{base}/devices/{rtsp_cam['id']}/visitor-entrance", "manager-sol", {"enabled": False})

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
        row = pan.get_by_role("listitem").filter(has_text=re.compile("Ticket \\d+ · Visitante Saida E2E"))
        ticket = re.search(r"Ticket (\d+)", row.inner_text()).group(1)
        pan.get_by_label("Ticket de saída", exact=True).fill(ticket)
        pan.get_by_role("button", name="Validar ticket").click()
        expect(pan.get_by_text(re.compile(f"Saída registrada: Visitante Saida E2E \\(ticket {ticket}\\)"))).to_be_visible()
        expect(pan.get_by_text(re.compile("Visitante Saida E2E · unidade"))).to_have_count(0, timeout=15000)
        check("o ticket numerado do visitante é validado na saída e ele some da lista", True)

        # prestador sem convite: a portaria avisa, o morador autoriza, a portaria registra a entrada e depois a saída
        pan.get_by_label("Unidade visitada").select_option(label="A / 101")
        pan.get_by_label("Nome do visitante").fill("Prestador E2E")
        pan.get_by_label("Tipo").select_option("SERVICE_PROVIDER")
        pan.get_by_label("Empresa ou serviço (opcional)").fill("Net")
        pan.get_by_role("button", name="Pedir autorização ao morador").click()
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_text("Prestador E2E (Net)").first).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Autorizar").click()
        pan.get_by_role("button", name="Registrar entrada de Prestador E2E (Net)").click()
        expect(pan.get_by_text(re.compile("Entrega o ticket nº \\d+ a Prestador E2E"))).to_be_visible(timeout=15000)
        check("ao registrar a entrada na portaria a tela mostra o número do ticket a entregar", True)
        expect(pan.get_by_text(re.compile("Prestador E2E \\(Net\\) · unidade A / 101"))).to_be_visible(timeout=15000)
        check("prestador autorizado pelo morador tem a entrada registrada e passa a contar no local", True)
        pan.get_by_role("button", name="Registrar saída de Prestador E2E (Net)").click()
        expect(pan.get_by_text(re.compile("Prestador E2E \\(Net\\) · unidade A / 101"))).to_have_count(0, timeout=15000)
        check("a saída do prestador reduz o número de visitantes no local", True)

        # imagem da câmera de entrada junto do aviso: a portaria captura, confere a prévia e envia; o morador vê a imagem
        pan.get_by_label("Unidade visitada").select_option(label="A / 101")
        pan.get_by_label("Nome do visitante").fill("Entrega E2E")
        pan.get_by_label("Tipo").select_option("VISITOR")
        pan.get_by_role("button", name="Capturar imagem da entrada").click()
        expect(pan.get_by_role("img", name=re.compile("Prévia da imagem")).first).to_be_visible(timeout=15000)
        pan.get_by_role("button", name=re.compile(r"Pedir autorização ao morador \(com imagem\)")).click()
        res.get_by_role("button", name="Início", exact=True).click()
        expect(res.get_by_role("img", name="Imagem da entrada: Entrega E2E")).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Autorizar").click()
        check("a imagem da câmera de entrada vai junto do aviso e o morador a vê antes de autorizar", True)
        pan.get_by_role("button", name="Ver imagem de Entrega E2E").click()
        expect(pan.get_by_role("img", name="Imagem da entrada de Entrega E2E")).to_be_visible(timeout=15000)
        check("o operador vê a imagem da entrada de um pedido de visita pela tela", True)

        # "Meus acessos": leitores simulados de entrada e de saída; o morador cadastrado vê só os próprios horários.
        # O painel deste teste é o do operador (sem gestão): o meio de acesso é cadastrado pelo síndico, via API; a tela
        # de cadastro é coberta pelos testes de interface.
        me = api("GET", "/api/v1/me/residencies", "resident-sol")[0]
        leitor_in = api("POST", f"{base}/devices", "manager-sol", {"name": "Leitor entrada E2E", "kind": "ACCESS_CONTROLLER",
                        "adapter": "SIMULATOR", "entrance": "portaria", "config": {"direction": "IN"}})
        leitor_out = api("POST", f"{base}/devices", "manager-sol", {"name": "Leitor saida E2E", "kind": "ACCESS_CONTROLLER",
                         "adapter": "SIMULATOR", "entrance": "portaria", "config": {"direction": "OUT"}})
        api("POST", f"{base}/residents/{me['residentId']}/access-identities", "manager-sol",
            {"kind": "CARD", "ref": "E2E-CARD-0001"})
        now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        for dev, ref in ((leitor_in, "E2E-CARD-0001"), (leitor_out, "e2e-card-0001"), (leitor_in, "CARTAO-DESCONHECIDO")):
            device_event(dev["id"], dev["apiKey"], {"type": "DOOR_OPENED", "occurredAt": now, "identityRef": ref})
        more(res, "Meus acessos")
        expect(res.get_by_text(re.compile(r"Entrada · portaria · cartão"))).to_be_visible(timeout=15000)
        expect(res.get_by_text(re.compile(r"Saída · portaria · cartão"))).to_be_visible()
        check("o morador cadastrado vê a própria entrada e saída (referência desconhecida não aparece)",
              res.get_by_role("listitem").filter(has_text="cartão").count() == 2)
        expect(res.get_by_role("region", name="Sua foto de cadastro")).to_be_visible()
        res.get_by_role("checkbox").check()
        res.get_by_role("button", name="Autorizar").click()
        res.locator("#photo-file").set_input_files({"name": "sintetica.png", "mimeType": "image/png", "buffer": synthetic_png()})
        expect(res.get_by_role("img", name="Sua foto de cadastro")).to_be_visible(timeout=15000)
        check("morador autoriza o uso da foto, envia uma imagem sintética e a vê", True)
        res.get_by_role("button", name="Remover minha foto").click()
        expect(res.get_by_role("img", name="Sua foto de cadastro")).to_have_count(0, timeout=15000)
        check("o morador remove só a foto, mantendo o consentimento", res.get_by_role("button", name="Revogar consentimento").count() == 1)
        res.locator("#photo-file").set_input_files({"name": "sintetica.png", "mimeType": "image/png", "buffer": synthetic_png()})
        expect(res.get_by_role("img", name="Sua foto de cadastro")).to_be_visible(timeout=15000)
        res.get_by_role("button", name="Revogar consentimento").click()
        expect(res.get_by_role("button", name="Autorizar")).to_be_visible(timeout=15000)
        check("revogar o consentimento apaga a foto", res.get_by_role("img", name="Sua foto de cadastro").count() == 0)
        res.get_by_role("button", name="← Voltar ao início").click()
        expect(res.get_by_role("button", name="Último acesso")).to_contain_text("portaria", timeout=15000)
        check("o início mostra o último acesso do morador", True)
        more(res, "Equipe")
        expect(res.get_by_role("heading", name="Equipe da portaria")).to_be_visible()
        expect(res.get_by_role("region", name="Equipe").get_by_role("listitem").first).to_be_visible(timeout=15000)
        check("o morador vê a equipe da portaria (nome e função)",
              res.get_by_role("region", name="Equipe").get_by_text(re.compile("Portaria remota|Síndico")).count() > 0)
        res.get_by_role("button", name="← Voltar ao início").click()

        pan.get_by_role("tab", name="Relatórios").click()
        pan.get_by_role("button", name="Consultar").click()
        expect(pan.get_by_text(re.compile("Visitante Saida E2E")).first).to_be_visible()
        expect(pan.get_by_text(re.compile("saiu")).first).to_be_visible()
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
        print("== painel como síndico")
        mgr = browser.new_context(viewport={"width": 1280, "height": 900}, locale="pt-BR").new_page()
        watch(mgr, "painel-sindico")
        mgr.goto("http://localhost:5173")
        mgr.locator("#subject").select_option("manager-sol")
        mgr.get_by_role("button", name="Entrar").click()
        mgr.get_by_role("button", name=re.compile("Residencial Sol")).click()
        mgr.get_by_role("tab", name="Câmeras").click()
        expect(mgr.get_by_role("heading", name="Cadastrar câmera")).to_be_visible()
        mgr.get_by_label("Nome", exact=True).fill("Câmera síndico E2E")
        mgr.get_by_label("Entrada", exact=True).fill("Portaria")
        mgr.get_by_label("Endereço RTSP (opcional, sem usuário e senha)").fill("rtsp://127.0.0.1:8554/fake-src")
        mgr.get_by_role("button", name="Cadastrar câmera", exact=True).click()
        expect(mgr.get_by_text("Câmera síndico E2E").first).to_be_visible()
        check("síndico cadastra uma câmera pela tela", True)
        # edição da câmera, credenciais cifradas e marcação de entrada de visitas, tudo pela tela
        mgr.get_by_role("button", name="Editar Câmera síndico E2E").click()
        mgr.locator("#edit-name").fill("Câmera síndico E2E")
        mgr.locator("#edit-entrance").fill("Portaria principal")
        mgr.locator("#cred-user").fill("usuario-sintetico")
        mgr.locator("#cred-pass").fill("senha-sintetica-e2e")
        mgr.get_by_role("button", name="Salvar credenciais").click()
        expect(mgr.get_by_text(re.compile("Credenciais cadastradas"))).to_be_visible()
        mgr.get_by_role("button", name="Remover credenciais").click()
        expect(mgr.get_by_text("Nenhuma credencial cadastrada.")).to_be_visible()
        mgr.get_by_role("button", name="Salvar alterações").click()
        expect(mgr.get_by_role("region", name="Editar Câmera síndico E2E")).to_have_count(0)
        check("síndico edita a câmera e grava e remove as credenciais pela tela", True)
        mgr.get_by_role("button", name=re.compile("Marcar Câmera síndico E2E como câmera de entrada")).click()
        expect(mgr.get_by_role("button", name=re.compile("Desmarcar Câmera síndico E2E"))).to_be_visible()
        check("síndico marca a câmera como entrada de visitas pela tela", True)
        mgr.get_by_role("button", name="Liberar Câmera síndico E2E para o morador").click()
        expect(mgr.get_by_role("button", name="Retirar Câmera síndico E2E do morador")).to_be_visible()
        expect(mgr.get_by_role("region", name="Câmeras para moradores")).to_contain_text("liberadas")
        check("síndico libera a câmera ao morador e o quadro de cotas mostra a liberação", True)
        mgr.get_by_role("button", name="Retirar Câmera síndico E2E do morador").click()
        expect(mgr.get_by_role("button", name="Liberar Câmera síndico E2E para o morador")).to_be_visible()
        mgr.get_by_role("button", name="Desativar Câmera síndico E2E").click()
        expect(mgr.get_by_role("button", name="Reativar Câmera síndico E2E")).to_be_visible()
        check("síndico retira a liberação e desativa a câmera pela tela", True)
        mgr.get_by_role("tab", name="Portas").click()
        mgr.get_by_role("button", name="Atualizar estado").first.click()
        mgr.get_by_role("button", name="Habilitar").click()
        expect(mgr.get_by_text("habilitada", exact=True)).to_be_visible()
        mgr.get_by_role("button", name="Liberar abertura pelo morador").first.click()
        expect(mgr.get_by_role("button", name="Bloquear abertura pelo morador").first).to_be_visible()
        mgr.get_by_role("button", name="Bloquear abertura pelo morador").first.click()
        mgr.get_by_role("button", name="Desligar").click()
        expect(mgr.get_by_text("desligada", exact=True)).to_be_visible()
        mgr.get_by_role("button", name=re.compile("Girar chave de Leitor entrada E2E")).click()
        mgr.get_by_role("button", name="Confirmar troca").click()
        expect(mgr.get_by_text(re.compile("Nova chave de"))).to_be_visible()
        mgr.get_by_role("button", name="Já guardei").click()
        check("síndico atualiza o estado, liga a abertura remota, libera a porta e gira a chave pela tela", True)
        open_registry(mgr)
        mgr.get_by_role("button", name="Moradores", exact=True).click()
        mgr.locator("#res-unit").select_option(label="A / 101")
        mgr.get_by_role("button", name=re.compile("Meios de acesso de")).first.click()
        expect(mgr.get_by_text(re.compile("Foto de cadastro:"))).to_be_visible()
        mgr.locator("#ai-ref").fill(f"E2E-CARD-{secrets.token_hex(2)}")
        mgr.get_by_role("button", name="Cadastrar meio de acesso").click()
        mgr.get_by_role("button", name=re.compile("^Revogar .* de ")).last.click()
        expect(mgr.get_by_text("revogado").first).to_be_visible()
        check("síndico cadastra e revoga um meio de acesso do morador pela tela", True)
        mgr.get_by_role("button", name="Veículos", exact=True).click()
        expect(mgr.get_by_text(re.compile("E2E1A23"))).to_be_visible()
        mgr.get_by_label("Buscar por placa").fill("E2E1A23")
        mgr.get_by_role("button", name="Buscar", exact=True).click()
        expect(mgr.get_by_text(re.compile("E2E1A23"))).to_be_visible()
        check("síndico consulta e busca veículos dos moradores pela placa", True)
        close_registry(mgr)
        mgr.get_by_role("tab", name="Ocorrências").click()
        mgr.get_by_label("Título").fill("Lâmpada queimada E2E")
        mgr.get_by_role("button", name="Abrir incidente").click()
        expect(mgr.get_by_text(re.compile(r"Lâmpada queimada E2E — OPEN"))).to_be_visible()
        mgr.get_by_role("listitem").filter(has_text="Lâmpada queimada E2E").get_by_role("button", name="Resolver").click()
        expect(mgr.get_by_text(re.compile(r"Lâmpada queimada E2E — RESOLVED"))).to_be_visible()
        check("síndico abre e resolve um incidente pela tela", True)
        # cadastros pelo síndico: bloco, unidade, morador, acesso ao app, desativar, exportar, anonimizar, equipe
        suf = secrets.token_hex(3)
        open_registry(mgr)
        mgr.get_by_role("button", name="Unidades", exact=True).click()
        mgr.get_by_label("Nome do bloco ou torre").fill(f"Torre E2E {suf}")
        mgr.get_by_role("button", name="Cadastrar bloco").click()
        expect(mgr.get_by_text(f"Torre E2E {suf}").first).to_be_visible()
        mgr.get_by_label("Bloco", exact=True).select_option(label=f"Torre E2E {suf}")
        mgr.get_by_label("Número", exact=True).fill("901")
        mgr.get_by_role("button", name="Cadastrar unidade").click()
        expect(mgr.get_by_text(re.compile(rf"Torre E2E {suf}.*901"))).to_be_visible()
        check("síndico cadastra bloco e unidade pela tela", True)
        mgr.get_by_role("button", name="Moradores", exact=True).click()
        mgr.get_by_label("Unidade", exact=True).select_option(label=f"Torre E2E {suf} / 901")
        mgr.get_by_label("Nome completo").fill(f"Morador Sintetico {suf}")
        mgr.get_by_label("Vínculo").select_option(index=0)
        mgr.get_by_role("button", name="Cadastrar morador").click()
        expect(mgr.get_by_text("Morador cadastrado.")).to_be_visible()
        expect(mgr.get_by_text(f"Morador Sintetico {suf}").first).to_be_visible()
        mgr.get_by_role("button", name=f"Acesso ao app de Morador Sintetico {suf}").click()
        mgr.locator("#acc-subject").fill(f"e2e-sub-{suf}")
        mgr.locator("#acc-email").fill(f"e2e-{suf}@example.invalid")
        mgr.get_by_role("button", name="Conceder acesso").click()
        expect(mgr.get_by_text("Acesso ao app concedido.")).to_be_visible()
        check("síndico cadastra um morador e concede acesso ao app pela tela", True)
        with mgr.expect_download() as dl2:
            mgr.get_by_role("button", name=f"Exportar dados de Morador Sintetico {suf}").click()
        check("exportação dos dados do morador baixa um JSON com o cadastro",
              f"Morador Sintetico {suf}" in open(dl2.value.path(), encoding="utf-8").read())
        mgr.get_by_role("button", name=f"Desativar Morador Sintetico {suf}").click()
        expect(mgr.get_by_role("button", name=f"Reativar Morador Sintetico {suf}")).to_be_visible()
        mgr.get_by_role("button", name=f"Reativar Morador Sintetico {suf}").click()
        expect(mgr.get_by_role("button", name=f"Desativar Morador Sintetico {suf}")).to_be_visible()
        check("síndico desativa e reativa o morador pela tela", True)
        mgr.once("dialog", lambda d: d.accept())
        mgr.get_by_role("button", name=f"Anonimizar Morador Sintetico {suf}").click()
        expect(mgr.get_by_text("Cadastro anonimizado.")).to_be_visible()
        check("anonimizar apaga os dados pessoais do morador pela tela", mgr.get_by_text(f"Morador Sintetico {suf}").count() == 0)
        mgr.get_by_role("button", name="Áreas comuns", exact=True).click()
        mgr.get_by_label("Nome da área").fill(f"Salão E2E {suf}")
        mgr.get_by_label("Capacidade (pessoas)").fill("30")
        mgr.get_by_role("button", name="Cadastrar área").click()
        expect(mgr.get_by_text(f"Salão E2E {suf}", exact=True)).to_be_visible()
        check("síndico cadastra uma área comum pela tela", True)
        more(res, "Reservas")
        res.locator("#r-area").select_option(label=f"Salão E2E {suf} (até 30 pessoas)")
        res.locator("#r-start").fill("2030-05-10T18:00")
        res.get_by_role("button", name="Reservar").click()
        expect(res.get_by_text(re.compile(rf"Salão E2E {suf}.*CONFIRMED"))).to_be_visible()
        check("morador reserva a área comum pelo app", True)
        mgr.get_by_role("button", name="Moradores", exact=True).click()
        mgr.get_by_role("button", name="Áreas comuns", exact=True).click()
        mgr.get_by_role("button", name=re.compile(rf"Cancelar reserva de Salão E2E {suf}")).click()
        expect(mgr.get_by_text(re.compile(rf"Salão E2E {suf} · unidade .* cancelada"))).to_be_visible()
        check("a equipe cancela a reserva do morador pela tela", True)
        more(res, "Reservas")
        res.locator("#r-area").select_option(label=f"Salão E2E {suf} (até 30 pessoas)")
        res.locator("#r-start").fill("2030-06-10T18:00")
        res.get_by_role("button", name="Reservar").click()
        mine = res.get_by_role("listitem").filter(has_text=re.compile(r"Salão E2E .*CONFIRMED"))
        expect(mine).to_have_count(1)
        mine.get_by_role("button", name="Cancelar").click()
        expect(res.get_by_role("listitem").filter(has_text=re.compile(r"Salão E2E .*CONFIRMED"))).to_have_count(0)
        check("o morador cancela a própria reserva pelo app", True)
        more(res, "Convites")
        item = res.get_by_role("listitem").filter(has_text="Visitante E2E").first
        item.get_by_role("button", name="Revogar").click()
        expect(res.get_by_role("listitem").filter(has_text="Visitante E2E").first).to_contain_text("Revogado")
        check("morador revoga o convite pelo app", True)
        more(res, "Perfil")
        parcels_pref = res.get_by_label("Avisar sobre encomendas")
        parcels_pref.uncheck()
        res.reload()
        res.get_by_role("button", name="Entrar como morador de teste").click()
        more(res, "Perfil")
        expect(res.get_by_label("Avisar sobre encomendas")).not_to_be_checked()
        res.get_by_label("Avisar sobre encomendas").check()
        check("a preferência de aviso do morador persiste após recarregar", True)
        mgr.get_by_role("button", name="Equipe", exact=True).click()
        mgr.get_by_role("button", name="Carregar equipe").click()
        expect(mgr.get_by_label("Equipe").get_by_role("listitem").first).to_be_visible()
        check("síndico vê a equipe do condomínio pela tela", True)
        # regra: só o administrador da empresa altera a equipe; o síndico recebe 403 e a tela explica (o 403 é esperado aqui)
        known = len(errors), len(failed_requests)
        mgr.locator("#staff-subject").fill(f"e2e-op-{suf}")
        mgr.locator("#staff-email").fill(f"e2e-op-{suf}@example.invalid")
        mgr.locator("#staff-name").fill(f"Operador Sintetico {suf}")
        mgr.get_by_role("button", name="Adicionar", exact=True).click()
        expect(mgr.get_by_text("Só o administrador da empresa operadora altera a equipe.")).to_be_visible()
        check("o síndico não adiciona equipe e a tela explica que é do administrador da empresa", True)
        del errors[known[0]:], failed_requests[known[1]:]
        mgr.get_by_role("button", name="Animais", exact=True).click()
        mgr.locator("#pet-unit").select_option(label="A / 101")
        mgr.locator("#pet-name").fill(f"Pet Painel {suf}")
        mgr.get_by_role("button", name="Cadastrar animal").click()
        expect(mgr.get_by_text(f"Pet Painel {suf}")).to_be_visible()
        mgr.get_by_role("button", name=f"Editar Pet Painel {suf}").click()
        mgr.locator("#pet-name").fill(f"Pet Painel {suf} B")
        mgr.get_by_role("button", name="Salvar alterações").click()
        expect(mgr.get_by_text(f"Pet Painel {suf} B")).to_be_visible()
        mgr.get_by_role("button", name=f"Remover Pet Painel {suf} B").click()
        expect(mgr.get_by_text(f"Pet Painel {suf}")).to_have_count(0)
        check("síndico cadastra, edita e remove um animal pela tela", True)
        close_registry(mgr)
        mgr.get_by_role("tab", name="Relatórios").click()
        mgr.get_by_role("button", name="Resumo da atividade").click()
        expect(mgr.get_by_role("region", name="Resumo da atividade")).to_be_visible()
        mgr.get_by_role("button", name="Atividade por unidade").click()
        expect(mgr.get_by_role("region", name="Atividade por unidade")).to_be_visible()
        with mgr.expect_download() as dl3:
            mgr.get_by_role("button", name="Exportar resumo (CSV)").click()
        check("síndico vê o resumo da atividade, a atividade por unidade e baixa o CSV",
              open(dl3.value.path(), encoding="utf-8").read().strip() != "")
        with mgr.expect_download() as dl4:
            mgr.get_by_role("button", name="Exportar por unidade (CSV)").click()
        check("síndico baixa o CSV da atividade por unidade", open(dl4.value.path(), encoding="utf-8").read().strip() != "")
        # associação: pessoa nova pede acesso com o código do síndico e o síndico aprova
        open_registry(mgr)
        mgr.get_by_role("button", name="Solicitações", exact=True).click()
        mgr.get_by_role("button", name=re.compile("^Gerar")).click()
        expect(mgr.get_by_label("Código de adesão")).to_be_visible(timeout=15000)
        join_code = mgr.get_by_label("Código de adesão").inner_text()
        novato = browser.new_context(viewport={"width": 390, "height": 900}, locale="pt-BR").new_page()
        watch(novato, "novato")
        novato.goto(f"http://localhost:5174/?devSubject=novato-{suf}")
        novato.get_by_role("button", name="Entrar como morador de teste").click()
        novato.locator("#join-code").fill("CODIGOERRADO")
        novato.locator("#join-name").fill(f"Novato E2E {suf}")
        novato.locator("#join-email").fill(f"novato-{suf}@example.invalid")
        novato.locator("#join-unit").fill("Bloco A, 101")
        novato.get_by_role("button", name="Enviar pedido").click()
        expect(novato.get_by_role("alert")).to_be_visible(timeout=15000)
        novato.locator("#join-code").fill(join_code)
        novato.get_by_role("button", name="Enviar pedido").click()
        expect(novato.get_by_text(re.compile("aguardando o síndico"))).to_be_visible(timeout=15000)
        mgr.get_by_role("button", name="Moradores", exact=True).click()
        mgr.get_by_role("button", name="Solicitações", exact=True).click()
        mgr.get_by_label(f"Unidade de Novato E2E {suf}").select_option(label="A / 101")
        mgr.get_by_role("button", name=f"Aprovar Novato E2E {suf}").click()
        expect(mgr.get_by_text(f"Novato E2E {suf}")).to_have_count(0, timeout=15000)
        novato.reload()
        novato.get_by_role("button", name="Entrar como morador de teste").click()
        expect(novato.get_by_text(re.compile("Unidade 101"))).to_be_visible(timeout=15000)
        check("pessoa nova pede acesso com o código de adesão e o síndico aprova pela tela", True)
        # respostas esperadas desse roteiro: 403 (ainda sem vínculo) e 404 (código errado); qualquer outra falha do novato continua valendo
        expected = ("403 GET http://localhost:5174/api/v1/me/residencies", "404 POST http://localhost:5174/api/v1/association-requests")
        failed_requests[:] = [r for r in failed_requests if not (r.startswith("novato:") and r.endswith(expected))]
        errors[:] = [e for e in errors if not (e.startswith("novato:") and ("status of 403" in e or "status of 404" in e))]
        mtokens = mgr.evaluate("() => JSON.stringify([localStorage, sessionStorage])")
        check("nenhum token guardado em localStorage/sessionStorage (síndico)", "eyJ" not in mtokens)

        print("== painel como administrador da empresa")
        adm = browser.new_context(viewport={"width": 1280, "height": 900}, locale="pt-BR").new_page()
        watch(adm, "painel-admin")
        adm.goto("http://localhost:5173")
        adm.locator("#subject").select_option("alpha-admin")
        adm.get_by_role("button", name="Entrar").click()
        adm.get_by_role("button", name="Administração").click()
        expect(adm.get_by_role("tab", name="Empresas")).to_have_count(0)  # só o administrador da plataforma vê Empresas
        adm.get_by_role("tab", name="Condomínios").click()
        novo = f"Condomínio E2E {secrets.token_hex(3)}"
        adm.get_by_label("Nome do condomínio").fill(novo)
        adm.get_by_role("button", name="Criar condomínio").click()
        expect(adm.get_by_text("Condomínio criado.")).to_be_visible()
        expect(adm.get_by_text(novo).first).to_be_visible()
        check("administrador da empresa cria um condomínio pela tela", True)
        adm.get_by_role("tab", name="Agentes e sincronização").click()
        adm.get_by_label("Condomínio", exact=True).select_option(label=novo)
        adm.get_by_label("Nome do novo agente").fill("Agente E2E")
        adm.get_by_role("button", name="Cadastrar agente").click()
        expect(adm.get_by_text("aparecem só agora")).to_be_visible()
        agent_key = adm.locator("code").first.inner_text()
        adm.get_by_role("button", name="Já guardei").click()
        expect(adm.get_by_text("aparecem só agora")).to_have_count(0)
        expect(adm.get_by_text(re.compile("Agente E2E · "))).to_be_visible()
        check("administrador cadastra um agente; as credenciais aparecem uma só vez", True)
        novo_id = next(c["id"] for c in api("GET", "/api/v1/condominiums?size=100", "alpha-admin")["items"] if c["name"] == novo)
        agent_id = api("GET", f"/api/v1/condominiums/{novo_id}/agents", "alpha-admin")[0]["id"]
        batch = {"events": [{"eventId": str(__import__("uuid").uuid4()), "type": "OFFLINE_ENTRY_UNCERTAIN",
                             "credentialId": str(__import__("uuid").uuid4()),
                             "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "entrance": "portaria", "reason": "e2e"}]}
        ev = urllib.request.Request("http://localhost:8081/api/v1/agent/events", method="POST", data=json.dumps(batch).encode(),
                                    headers={"X-Agent-Id": agent_id, "X-Agent-Key": agent_key, "Content-Type": "application/json"})
        urllib.request.urlopen(ev).read()
        adm.get_by_role("tab", name="Condomínios").click()
        adm.get_by_role("tab", name="Agentes e sincronização").click()
        adm.get_by_label("Condomínio", exact=True).select_option(label=novo)
        adm.get_by_role("button", name=re.compile("^Resolver conflito")).click()
        expect(adm.get_by_text("resolvido", exact=True).first).to_be_visible(timeout=15000)
        check("o administrador resolve um conflito de sincronização do agente pela tela", True)
        adm.get_by_role("button", name="Revogar agente Agente E2E").click()
        expect(adm.get_by_text(re.compile("Agente E2E · .*revogado"))).to_be_visible()
        check("administrador revoga o agente pela tela", True)
        # equipe do condomínio novo: só o administrador da empresa adiciona e revoga
        adm.get_by_role("button", name="Administração").click()
        adm.get_by_role("button", name=re.compile(re.escape(novo))).click()
        open_registry(adm)
        adm.get_by_role("button", name="Equipe", exact=True).click()
        adm.locator("#staff-subject").fill(f"e2e-rev-{suf}")
        adm.locator("#staff-email").fill(f"e2e-rev-{suf}@example.invalid")
        adm.locator("#staff-name").fill(f"Operador Revogado {suf}")
        adm.get_by_role("button", name="Adicionar", exact=True).click()
        expect(adm.get_by_text(f"Operador Revogado {suf}")).to_be_visible()
        adm.get_by_role("button", name=f"Revogar Operador Revogado {suf}").click()
        expect(adm.get_by_text(f"Operador Revogado {suf}")).to_have_count(0)
        check("o administrador da empresa adiciona e revoga um operador pela tela", True)
        atokens = adm.evaluate("() => JSON.stringify([localStorage, sessionStorage])")
        check("nenhum token guardado em localStorage/sessionStorage (administrador)", "eyJ" not in atokens)

        print("== painel como administrador da plataforma")
        plat = browser.new_context(viewport={"width": 1280, "height": 900}, locale="pt-BR").new_page()
        watch(plat, "painel-plataforma")
        plat.goto("http://localhost:5173")
        plat.locator("#subject").select_option("platform-admin")
        plat.get_by_role("button", name="Entrar").click()
        plat.get_by_role("button", name="Administração").click()
        plat.get_by_role("tab", name="Empresas").click()
        emp = f"Empresa E2E {secrets.token_hex(3)}"
        plat.get_by_label("Nome da empresa").fill(emp)
        plat.get_by_label("CNPJ (só dígitos)").fill(str(int(time.time() * 1000))[-14:].zfill(14))
        plat.get_by_role("button", name="Cadastrar empresa").click()
        expect(plat.get_by_text("Empresa cadastrada.")).to_be_visible()
        plat.locator("#ad-company").select_option(label=emp)
        plat.locator("#ad-subject").fill(f"e2e-adm-{suf}")
        plat.locator("#ad-email").fill(f"e2e-adm-{suf}@example.invalid")
        plat.locator("#ad-name").fill(f"Admin Sintetico {suf}")
        plat.get_by_role("button", name="Adicionar administrador").click()
        expect(plat.get_by_text("Administrador adicionado.")).to_be_visible()
        expect(plat.get_by_label("Administradores da empresa").get_by_text(f"Admin Sintetico {suf}")).to_be_visible()
        check("a plataforma cadastra uma empresa e seu administrador pela tela", True)
        plat.once("dialog", lambda d: d.accept())
        plat.get_by_role("button", name=f"Revogar administrador Admin Sintetico {suf}").click()
        expect(plat.get_by_text("Administrador revogado.")).to_be_visible()
        expect(plat.get_by_label("Administradores da empresa").get_by_text(f"Admin Sintetico {suf}")).to_have_count(0)
        check("a plataforma revoga o administrador da empresa pela tela", True)
        plat.once("dialog", lambda d: d.accept())
        plat.get_by_role("button", name=f"Suspender empresa {emp}").click()
        expect(plat.get_by_text("Empresa suspensa.")).to_be_visible()
        expect(plat.get_by_role("button", name=f"Reativar empresa {emp}")).to_be_visible()
        plat.once("dialog", lambda d: d.accept())
        plat.get_by_role("button", name=f"Reativar empresa {emp}").click()
        expect(plat.get_by_text("Empresa reativada.")).to_be_visible()
        check("a plataforma suspende e reativa a empresa pela tela", True)
        plat.get_by_role("tab", name="Condomínios").click()
        plat.once("dialog", lambda d: d.accept())
        plat.get_by_role("button", name=f"Suspender condomínio {novo}").click()
        expect(plat.get_by_text("Condomínio suspenso.")).to_be_visible()
        plat.once("dialog", lambda d: d.accept())
        plat.get_by_role("button", name=f"Reativar condomínio {novo}").click()
        expect(plat.get_by_text("Condomínio reativado.")).to_be_visible()
        check("a plataforma suspende e reativa um condomínio pela tela", True)

        # chamadas do condomínio: registrar uma chamada de balcão, atendê-la e transferi-la a outro operador de plantão
        api("POST", "/api/v1/operator/shifts/start", "operator-multi")
        pan.get_by_role("tab", name="Chamadas").click()
        pan.get_by_label("Unidade (opcional)").select_option(label="A / 101")
        pan.get_by_label("Nome de quem chamou (opcional)").fill("Balcão E2E")
        pan.get_by_role("button", name="Registrar chamada").click()
        expect(pan.get_by_text(re.compile("Balcão E2E"))).to_be_visible(timeout=15000)
        check("a portaria registra e lista as chamadas do condomínio pela tela", True)
        pan.get_by_role("button", name="Início", exact=True).click()
        take_next(pan)
        pan.get_by_label("Operador de plantão").select_option(label="Operador Multi")
        pan.get_by_label("Motivo", exact=True).fill("Passagem de turno E2E")
        pan.get_by_role("button", name="Transferir", exact=True).click()
        expect(pan.get_by_role("button", name="Encerrar atendimento")).to_have_count(0, timeout=15000)
        check("o operador transfere o atendimento a outro operador de plantão pela tela", True)

        pan.get_by_role("button", name="Encerrar turno").click()
        pan.get_by_role("button", name="Confirmar encerramento").click()
        expect(pan.get_by_text("Sem turno")).to_be_visible()
        check("o operador encerra o turno pela tela", True)
        tokens = pan.evaluate("() => JSON.stringify([localStorage, sessionStorage])")
        check("nenhum token guardado em localStorage/sessionStorage (painel)", "eyJ" not in tokens)
        # síndico e administrador não são operadores: o escopo (me/admin-scope) evita a consulta de atendimentos, então nenhum 403 é tolerado
        check("sem erros de JavaScript/console nas duas telas", not errors, str(errors[:3]) + (" | requisições com erro: " + str(failed_requests[:6]) if errors else ""))
        if os.environ.get("E2E_TRACE_REQUESTS"):
            with open(os.environ["E2E_TRACE_REQUESTS"], "w") as f:
                json.dump(sorted(traced), f)
        browser.close()
finally:
    cleanup()
print("RESULTADO:", "FALHOU" if failed else "OK")
sys.exit(1 if failed else 0)
