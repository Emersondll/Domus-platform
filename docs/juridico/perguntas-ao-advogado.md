# Perguntas ao advogado (rascunho)

Cada item: o que o sistema faz hoje → a pergunta → a recomendação técnica (que NÃO substitui a resposta jurídica) → o que muda no sistema conforme a resposta.

## D1 — Papéis, base legal, encarregado, RIPD
- **Hoje:** o sistema trata cadastro de moradores, visitantes, placas, tentativas de acesso, mensagens e livro de turno (ver `inventario-de-dados.md`).
- **Perguntas:** (a) quem é controlador e quem é operador entre condomínio, empresa de portaria e a plataforma (art. 39 — para conferência)? (b) qual a base legal de cada
  tratamento (execução de contrato/legítimo interesse/consentimento)? (c) é preciso indicar encarregado (DPO) e quem? (d) é exigido RIPD para este tratamento?
  (e) qual o procedimento de incidente e a notificação à ANPD e aos titulares?
- **Recomendação técnica:** condomínio como controlador e empresa de portaria como operadora (como em `docs/lgpd.md`); a plataforma, como operadora/suboperadora.
- **Muda no sistema:** textos de privacidade, canal do titular e contratos; nenhuma mudança de código se mantida a arquitetura.

## D19 — Prazo do histórico de entradas/saídas do morador
- **Hoje:** 90 dias, visível só ao próprio morador. **Pergunta:** o prazo é adequado à finalidade (o morador consultar o próprio histórico)? Há prazo mínimo ou máximo exigível?
- **Muda no sistema:** só a configuração `RESIDENT_ACCESS_RETENTION_DAYS`.

## D21 e CR6 — Câmeras de entrada vistas ao vivo pelo morador; "ciência"
- **Hoje:** o síndico libera por câmera (padrão: nenhuma), 24 h, sem gravação, cada abertura auditada. A "ciência" do morador vale só enquanto a tela está aberta e **não é registrada**.
- **Perguntas:** (a) base legal; (b) o aviso na entrada (ver `aviso-de-cameras-rascunho.md`) basta? (c) a ciência deve ser registrada (quem, versão do texto, quando) e o morador pode recusar sem perder o acesso ao app?
  (d) a imagem de visitantes e prestadores exposta a todos os moradores é aceitável, com que restrições (horário, quais câmeras)?
- **Recomendação técnica:** registrar a ciência com versão do texto e data (fácil de implementar depois que o texto estiver aprovado); manter o recurso desligado por padrão.
- **Muda no sistema:** nova tela de aceite e tabela de registro; texto final da placa.

## D16 e D18 — Foto de cadastro e identificação facial
- **Hoje:** a foto de cadastro existe **desligada** (`RESIDENT_PHOTO_ENABLED=false`): consentimento específico e versionado, cifrada, revogável (apaga na hora). **Não há gabarito facial nem reconhecimento.**
- **Perguntas:** (a) a foto de cadastro é dado sensível neste uso? (b) a identificação facial para entrada tem base legal possível neste contexto (art. 11 — para conferência) ou só consentimento destacado? (c) qual alternativa sem biometria é obrigatória? (d) é aceitável o gabarito morar no terminal e não na central (D18)? (e) exige RIPD e DPO específicos?
- **Recomendação técnica:** manter desligado; não coletar gabarito antes das respostas; se avançar, gabarito apenas no terminal, alternativa sem biometria sempre disponível.
- **Muda no sistema:** liga o recurso e, no futuro, o fluxo do terminal. Depende também de D2 (equipamento).

## Menores e dependentes
- **Hoje:** dependentes são cadastrados por um adulto, sem acesso ao app. **Pergunta:** o consentimento/ciência de um adulto basta? (art. 14 — para conferência).

## Visitantes e prestadores (terceiros)
- **Hoje:** o morador informa o nome; ele fica cifrado e vinculado à unidade, sem prazo. **Perguntas:** prazo; como informar o visitante (aviso na portaria/convite); como atender o pedido de um visitante.

## T11 — Endereço IP na auditoria
- **Hoje:** a auditoria **não guarda IP** (só o tipo de cliente). O Keycloak guarda o IP dos logins por 30 dias. **Pergunta:** o registro de IP é necessário/justificável para segurança e quanto tempo pode ficar (Marco Civil da Internet pode ter prazos próprios — para conferência)?
- **Recomendação técnica:** só adotar se houver necessidade clara; com prazo de descarte próprio e sem misturar com a trilha que nunca expira.

## Transferência internacional (D6)
- **Hoje:** o provedor de hospedagem não foi escolhido. **Pergunta:** se o servidor ficar fora do Brasil, que cláusulas e avisos são necessários? A recomendação técnica é hospedar no Brasil para evitar a questão.

## Abertura remota de porta pelo morador e comunicações
- **Hoje:** opcional, desligada por padrão, ativada pelo síndico por porta, auditada. **Pergunta:** que termo o morador precisa aceitar e quem responde por abertura indevida?
