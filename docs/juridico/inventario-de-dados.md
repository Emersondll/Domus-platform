# Inventário de dados pessoais (rascunho técnico — não é RIPD)

Estado do sistema em 2026-11-03. "Cifrado" = cifragem em nível de campo na aplicação (AES-256-GCM, chaves fora do banco). Tudo é isolado por condomínio.
Base legal e papel (controlador/operador) **não** estão definidos aqui: ver `perguntas-ao-advogado.md`.

| Dado | De quem | Para quê (finalidade técnica) | Onde fica | Protegido | Quem acessa | Prazo hoje |
|---|---|---|---|---|---|---|
| Nome, e-mail, telefone do morador (e cônjuge/dependentes) | morador | cadastro, avisos, chamada | `resident` | cifrado | portaria e síndico; o próprio morador | enquanto o cadastro existir; **anonimizável** a pedido |
| Conta de acesso (e-mail, nome) | morador, equipe | login e vínculo com o condomínio | `app_user` e **Keycloak** | cifrado no banco; Keycloak guarda senha em hash e o segredo do OTP | o próprio usuário; administração da instalação | enquanto a conta existir |
| Nome do visitante / prestador | morador que convida, portaria | convite, autorização, histórico | `visitor`, `visit_request` | cifrado | portaria, síndico; o morador da unidade | **sem prazo de expurgo definido** (D1) |
| Placa, modelo e cor do veículo | morador | cadastro de veículos | `vehicle` | placa cifrada | portaria, síndico; o morador da unidade | enquanto cadastrado |
| Animais (nome, espécie, observação livre) | morador | cadastro | `pet` | — (sem dado pessoal além do vínculo à unidade) | portaria, síndico | enquanto cadastrado |
| Tentativas de acesso (quem entrou, quando, por qual entrada) | morador, visitante | segurança e histórico da portaria | `access_attempt` | nome do visitante cifrado | portaria, síndico | **365 dias** |
| Histórico pessoal de entradas e saídas do morador ("Meus acessos") | morador | consulta pelo próprio morador | `resident_access_event` | — | **só o próprio morador** (síndico e portaria recebem 404) | **90 dias** (D19, a confirmar) |
| Meios de acesso do morador (cartão/usuário do terminal) | morador | ligar a leitura da controladora ao cadastro | `access_identity` | cifrado | administração | enquanto cadastrado |
| Foto de cadastro | morador | futura entrada por reconhecimento facial | `resident_photo` | cifrada; 640 px / 200 KB | só o titular; portaria vê "tem foto: sim/não" | **recurso DESLIGADO** (`RESIDENT_PHOTO_ENABLED=false`); revogar apaga na hora |
| Gabarito facial (biometria) | — | — | **não existe** | — | — | **não coletado** (dado sensível, art. 5º II e 11 — para conferência) |
| Imagem da câmera de entrada (um quadro por pedido de visita) | visitante | mostrar ao morador quem chegou | `visit_snapshot` | cifrada; sem URL pública | morador da unidade; portaria que captura | **recurso DESLIGADO** (`SNAPSHOTS_ENABLED=false`); 10 min se não enviada, 30 dias se enviada |
| Vídeo ao vivo das câmeras | pessoas na entrada | operação da portaria; morador (câmeras liberadas pelo síndico) | **não é gravado** | sessão curta por token | portaria; morador só nas câmeras liberadas | sem gravação; cada abertura é auditada |
| Mensagens morador ↔ portaria | morador | atendimento | `direct_message` | texto cifrado | o morador e a portaria | **sem expurgo por padrão** (`DIRECT_MESSAGE_RETENTION_DAYS=0`) |
| Livro de turno | equipe | registro de ocorrências | `shift_log_entry` | texto cifrado (pode citar pessoas) | equipe do condomínio | sem prazo definido |
| Chamadas (nome de quem chamou, unidade) | morador, visitante | atendimento | `service_call` | nome cifrado | portaria, síndico | sem prazo definido |
| Notificações | morador | avisos | `notification` | — | o próprio morador | **90 dias** |
| Eventos de agente e de dispositivo | equipamentos | diagnóstico e sincronização | `agent_event`, `device_event` | — | equipe técnica | **90 dias** |
| **Auditoria** (quem fez o quê e quando) | equipe, morador | segurança e prova | `audit_log` | sem texto de mensagens nem imagens; **sem endereço IP** (só o tipo de cliente: painel, app ou API) | administração | **nunca expira** (tabela somente-inserção; descarte exige procedimento controlado) |
| Eventos de login do **Keycloak** (inclui **endereço IP** e horário) | todos que entram | segurança do login | banco `keycloak` | — | administração do Keycloak | **30 dias** (`eventsExpiration`) |
| Backups | tudo acima | recuperação de desastre | arquivos `.dump` (opcionalmente GPG) | checksum; criptografia opcional | quem opera o servidor | rotação local de 14; cópia de fora **a definir**; dado "eliminado" só sai do backup na rotação |

## Lacunas conhecidas (honestas)
- **Visitantes:** o nome fica vinculado à unidade sem prazo; não há como localizar o visitante para atender pedido de titular além do pedido em que aparece.
- **Logs do proxy e da aplicação:** os logs da API são JSON com identificador de correlação, sem corpo de requisição; **verificar** se o proxy (Caddy) grava endereço IP de acesso e por quanto tempo antes de afirmar qualquer coisa à ANPD.
- **Transferência internacional:** depende do provedor de hospedagem (D6, ainda não escolhido) e de qualquer serviço externo futuro (e-mail, WhatsApp).
- **Crianças e adolescentes:** dependentes são cadastrados por um adulto; o sistema não tem fluxo próprio de consentimento do responsável (art. 14, para conferência).
