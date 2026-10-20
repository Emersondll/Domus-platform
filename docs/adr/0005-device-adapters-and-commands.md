# ADR 0005 — Adaptadores de dispositivo e comandos físicos

**Status:** aceita.
**Contrato:** `DeviceAdapter` (`getStatus`, `capabilities`, `requestAccess`, `getAccessResult`). O adaptador só informa
se o comando foi **aceito**; nunca que a porta abriu.
**Estados do comando:** PENDING → SENT (aceito) → CONFIRMED (evento DOOR_OPENED do dispositivo/sensor, correlacionado
por `commandId` e pelo próprio dispositivo) | FAILED (recusado ou offline) | UNKNOWN (sem confirmação no prazo,
ou sem prova de entrega). UNKNOWN não presume abertura nem falha; uma confirmação tardia ainda o resolve.
**Autorização do comando:** exige (a) atendimento em andamento atribuído ao operador **ou** (b) validação de
credencial concedida há < 5 min (uso único: um `attemptId` autoriza um comando), mais justificativa ≥ 5 caracteres,
dispositivo compatível e idempotência por chave.
**Sem retry automático do envio:** repetir um comando físico às cegas pode abrir a porta duas vezes; o operador
reenvia com nova chave depois de verificar. Consultas de status são idempotentes e podem ser repetidas.
**Segurança:** a central só contata hosts da lista permitida (`portaria.devices.http-allowed-hosts`, anti-SSRF);
segredos de dispositivo ficam em variáveis de ambiente; eventos entram por `POST /api/v1/devices/{id}/events` com
chave do dispositivo (hash SHA-256 no banco, rotacionável); XML ONVIF é lido sem DTD (anti-XXE).
**Adaptadores:** `SIMULATOR` (dev/teste), `GENERIC_HTTP` (controladora com API HTTP documentada) e `ONVIF`
(status via `GetDeviceInformation`; não controla portas).
**Validação real pendente:** nenhum adaptador foi testado com hardware. `GENERIC_HTTP` e `ONVIF` foram validados
somente contra servidores HTTP locais de teste. Antes de produção: escolher o fabricante da primeira controladora,
implementar o adaptador específico conforme a documentação oficial e testar com o equipamento, incluindo a queda de rede.
**Não implementado:** SIP/telefonia, vídeo ao vivo (RTSP/`GetStreamUri`), descoberta automática de dispositivos.
