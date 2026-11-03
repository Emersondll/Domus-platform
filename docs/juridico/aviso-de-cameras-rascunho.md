# Aviso de câmeras — RASCUNHO (D21)

> Não é parecer jurídico. Lacunas `[ENTRE COLCHETES]` são do controlador. O advogado deve aprovar o texto, o tamanho da placa e os locais.

## Placa na entrada (texto curto, visível antes de entrar no campo da câmera)
**ÁREA MONITORADA POR CÂMERAS**
Imagens transmitidas ao vivo à portaria [e, nas câmeras liberadas pelo síndico, aos moradores deste condomínio pelo aplicativo]. **Não há gravação** [CONFIRMAR: se um dia houver gravação, este aviso deve ser refeito antes].
Finalidade: segurança e controle de acesso. Controlador: [NOME DO CONDOMÍNIO, CNPJ]. Dúvidas e direitos: [CANAL DO ENCARREGADO/E-MAIL].
Mais informações: [ONDE ESTÁ A POLÍTICA DE PRIVACIDADE].

## Texto no aplicativo (antes de abrir a primeira câmera)
> As câmeras da entrada mostram, ao vivo, pessoas que passam pelo local (visitantes, entregadores e outros moradores). Use a imagem **somente** para saber quem está à sua porta
> ou na entrada do condomínio. Não compartilhe, não grave a tela e não divulgue as imagens. Cada vez que você abre uma câmera, o sistema registra a abertura.
> [FRASE JURÍDICA SOBRE RESPONSABILIDADE DO MORADOR PELO USO]. Versão do aviso: [NÚMERO] — [DATA].

## O que o sistema faz (para o advogado conferir o texto acima)
- O síndico escolhe **quais** câmeras os moradores veem (padrão: nenhuma), sem limite de quantidade, 24 h por dia.
- **Sem gravação e sem histórico de vídeo.** Cada abertura de câmera pelo morador gera registro de auditoria (`camera.view.resident`).
- A transmissão usa sessão curta com token; não há URL pública permanente.
- O sistema **não** faz reconhecimento facial.
- Não é possível impedir que o morador fotografe a tela do celular.

## Pontos para decidir
1. A ciência do morador será registrada? (CR6, ver `termo-de-ciencia-e-consentimento-rascunho.md`)
2. Há restrição de horário ou de câmeras (por exemplo, excluir áreas de lazer)?
3. Onde exatamente ficam as placas e quem as mantém?
