# LGPD — o que é técnico e o que depende de decisão jurídica/operacional

> **Este documento não declara conformidade com a LGPD.** Controles técnicos são necessários, não suficientes: base legal,
> contratos, DPO, RIPD e resposta a incidentes são decisões organizacionais e jurídicas.

## Controles técnicos implementados
| Tema | Implementação |
|---|---|
| Minimização | QR Code sem dado pessoal; apenas nome/contato necessários; logs sem tokens/senhas |
| Controle de acesso | papéis por condomínio, 404 entre tenants, `PLATFORM_ADMIN` sem acesso a moradores |
| Registro de consultas sensíveis | listagem/leitura de morador, exportação e anonimização geram `audit_log` (append-only) |
| Direito de acesso/portabilidade | `GET .../residents/{id}/export` (JSON: cadastro, conta, preferências, notificações, convites, veículos/encomendas da unidade) |
| Direito de eliminação | `POST .../residents/{id}/anonymize`: anonimiza cadastro e conta, revoga acessos e convites ativos, apaga notificações e preferências |
| Privacidade do morador | preferências de aviso (encomenda, chegada de visitante) respeitadas por morador |
| Retenção | expurgo diário configurável: notificações 90 d, tentativas de acesso 365 d, eventos de agente/dispositivo 90 d, conflitos resolvidos 365 d (registros ainda referenciados são preservados) |
| Segurança | TLS, MFA para equipe, segregação por papéis, backups com checksum e criptografia opcional |

## Pendências técnicas
- **Imagens e áudios**: a plataforma **não** armazena nem grava. O vídeo ao vivo das câmeras (`docs/cameras.md`) é só transmissão para
  quem opera a portaria, e cada abertura de visualização é auditada (`camera.view`). Antes de ligar **gravação**, é obrigatório definir
  retenção, controle de acesso às gravações e registro de cada consulta.
- **`audit_log` não é expurgado automaticamente** (trigger append-only). O descarte exige procedimento de manutenção
  controlado e aprovado (desabilitar o trigger por DBA, em transação auditada) conforme a política de retenção definida pelo controlador.
- A anonimização não altera `audit_log` nem `access_attempt`/`access_command` (registros de segurança), que não contêm o nome do morador;
  visitantes (nome) permanecem vinculados à unidade — decidir política própria para dados de visitantes.
- Exportação cobre os dados que a plataforma conhece; dados em backups só saem na rotação dos backups.

## Decisões que cabem ao controlador (condomínio) e à operadora (empresa de portaria)
1. Papéis: em geral o **condomínio é controlador** e a **empresa de portaria é operadora** — formalizar em contrato (art. 39) e
   ajustar a quem o titular dirige o pedido.
2. Base legal para cada tratamento (visitantes, moradores, imagens), prazos de retenção finais e mecanismo de transparência.
3. Encarregado (DPO), canal do titular, **RIPD**, política de resposta a incidentes e notificação à ANPD/titulares.
4. Transferência internacional (provedores de nuvem/IdP/WhatsApp) e cláusulas contratuais.
5. Retenção de imagens e áudios quando houver gravação; acesso e justificativa para consulta.
6. Tratamento de dados de crianças/adolescentes e de terceiros (visitantes) cadastrados por moradores.

## Pedidos de autorização de visitante (visit_request)
O nome do visitante é dado pessoal: fica cifrado em nível de campo (`visit_request.visitor_name`), como nas demais tabelas. O pedido expira em 5 minutos sem resposta; o registro permanece para auditoria e deve entrar na política de retenção que o controlador definir (D1).

## Entrada de visitante autorizado na portaria (2026-10-26)
A portaria registra a entrada de quem o morador autorizou (`access_attempt.visit_request_id`, uma por pedido). O nome vem do pedido (cifrado) e aparece decifrado só no painel, no relatório e no CSV. Entra na mesma política de retenção do histórico de acessos (D1). Não há saída automática: quem ficou sem saída registrada por mais de 12 h recebe só um aviso na tela.

## Lote B: saída, relatório, comunicados, livro de turno e abertura remota
- **Saída de visitante:** `access_attempt.exited_at` guarda quando a portaria registrou a saída e quem registrou. Entra na mesma política de retenção do histórico de acessos (D1).
- **Relatório e CSV de acessos:** contém o nome do visitante (dado pessoal, decifrado só na leitura autorizada). Exige acesso de leitura ao condomínio, tem período máximo de 31 dias e 5.000 linhas, e **cada exportação grava auditoria** (`access.report.export`). Células iniciadas por `=`, `+`, `-`, `@` são neutralizadas contra injeção de fórmula em planilhas. O arquivo baixado sai do controle da plataforma: orientar quem exporta sobre guarda e descarte.
- **Livro de turno:** o texto é livre e pode citar pessoas; fica **cifrado em nível de campo** (`shift_log_entry.text`). A tela avisa para registrar fatos e evitar dados pessoais além do necessário. Prazo de retenção: decisão do controlador (D1).
- **Comunicados:** conteúdo institucional para todos os moradores; não deve conter dado pessoal de terceiros. Fica registrado com autor e data.
- **Abertura remota pelo morador:** recurso **opcional, desligado por padrão**; só o síndico o habilita e apenas nas portas marcadas. Cada pedido gera comando auditado, com limite de frequência por morador. É decisão do condomínio ativá-lo e comunicar as regras de uso aos moradores; a plataforma não presume que a porta abriu sem evento do equipamento.

## Mensagens individuais morador ↔ portaria (2026-10-25)
- Tabela `direct_message` (V12): texto cifrado na aplicação (AES-256-GCM); metadados (condomínio, morador, autor, horário, leitura) em claro.
- O morador só lê a própria conversa; a portaria (OPERATE) lê as conversas do condomínio. Fora do escopo = 404.
- A auditoria registra quem enviou/leu e quando, **sem o texto**; a notificação ao morador também não copia o texto.
- Limite de 10 mensagens por minuto por morador (429). Sem retenção/expurgo automático ainda: depende da política de retenção (pendência L-retenção).
- Não é canal de emergência; o app orienta usar o botão de pânico. Não declara conformidade com a LGPD.

## Família e animais (2026-10-25)
- Cônjuge e dependentes são cadastros de morador (`resident`, com nome e telefone cifrados) criados pelo próprio morador ou pela administração, sem acesso ao app até a administração conceder. Podem ser desativados, exportados e anonimizados como os demais.
- O morador só vê e altera a própria unidade; titular, proprietário e locatário só mudam pela administração (limite de 20 pessoas ativas por unidade).
- Animais (`pet`, V13) são dados do domicílio, sem dado pessoal além do vínculo com a unidade; o campo de observação é livre: orientar a não colocar dado sensível. Limite de 10 por unidade.
- Todas as operações gravam auditoria (sucesso na mesma transação). Não declara conformidade com a LGPD.
