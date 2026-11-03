# Ciência (câmeras) e consentimento (foto de cadastro) — RASCUNHOS

> Não é parecer jurídico. O advogado deve decidir **se** cada item é consentimento, ciência ou outra base legal. Lacunas `[ENTRE COLCHETES]`.

## A. Consentimento da foto de cadastro (D16) — texto que JÁ está no código
O sistema tem o recurso **desligado**. Quando ligado, o morador vê e aceita este texto (versão `2026-10-v1`, `ResidentPhotoService.TEXT`):

> Autorizo o uso da minha foto de cadastro somente para o controle de acesso ao condomínio. A foto é guardada cifrada, só eu a vejo e posso removê-la quando quiser:
> ao revogar, ela é apagada na hora. Recusar ou revogar não impede minha entrada, pois há alternativa sem foto (cartão ou QR Code). Nesta versão a foto não é usada
> para reconhecimento facial; isso só poderia ocorrer com um novo aviso e um novo aceite meu.

**O que o sistema já registra:** o aceite (quem, qual versão do texto, quando), a revogação (apaga a foto) e a inclusão da foto na exportação e na anonimização do titular.
**Pontos para o advogado:** (1) o texto cumpre "consentimento específico e destacado" se um dia houver gabarito facial? Provavelmente não — exigiria texto novo e novo aceite; (2) falta citar o controlador e o canal do titular: `[CONTROLADOR]`, `[CANAL]`;
(3) dependentes: quem consente? (art. 14 — para conferência); (4) o prazo de guarda da foto: `[ENQUANTO MORAR / PRAZO]`.

## B. Ciência do morador sobre as câmeras (CR6, D21) — NÃO implementado
**Hoje:** o morador vê um aviso na tela antes de abrir uma câmera, mas a ciência **não é registrada** e vale só enquanto a tela está aberta.
**Proposta técnica (só depois de o texto ser aprovado):** primeira abertura de câmera exige "Li e estou ciente" do texto abaixo; o sistema registra morador, versão do texto e data, e pede novo aceite quando a versão mudar. Recusar apenas impede de ver câmeras (não afeta o resto do app).

> **Câmeras da entrada.** As câmeras mostram, ao vivo, pessoas que passam pela entrada, incluindo visitantes, entregadores e outros moradores. Estou ciente de que devo usar
> a imagem somente para saber quem está à minha porta ou na entrada do condomínio, que não devo gravar, compartilhar nem divulgar as imagens, e que cada abertura de câmera
> é registrada pelo sistema. [RESPONSABILIDADE DO MORADOR PELO USO INDEVIDO — REDAÇÃO JURÍDICA]. Controlador: `[CONDOMÍNIO]`. Dúvidas: `[CANAL]`.
> Versão: `[AAAA-MM-vN]`.

## C. Abertura remota de porta pelo morador
Recurso opcional, ativado pelo síndico por porta. **Proposta de ciência:** o morador está ciente de que cada pedido de abertura é registrado, de que a porta só é considerada aberta
quando o equipamento confirma e de que `[REGRAS DE USO DEFINIDAS PELO CONDOMÍNIO]`. A redação sobre responsabilidade por abertura indevida é jurídica.
