package br.com.portaria.platform.devices.adapter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Contrato independente de fabricante. Nem todo dispositivo suporta todas as operações: consulte
 * {@link #capabilities()} antes de usar. Nenhuma implementação pode declarar que a porta abriu; ela só informa
 * se o comando foi <b>aceito</b>. A confirmação vem de evento do dispositivo/sensor ou de {@link #getAccessResult}.
 */
public interface DeviceAdapter {

    /** Capacidades possíveis de um dispositivo. */
    enum Capability { STATUS, OPEN_DOOR, EVENTS, VIDEO_STREAM }

    /** Estado de comunicação observado. */
    enum Health { ONLINE, OFFLINE, UNKNOWN }

    /**
     * Dados do dispositivo de que o adaptador precisa.
     *
     * @param id            identificador do dispositivo
     * @param condominiumId condomínio dono
     * @param entrance      entrada controlada
     * @param config        configuração não secreta (segredos vêm de variáveis de ambiente)
     */
    record DeviceDescriptor(UUID id, UUID condominiumId, String entrance, Map<String, String> config) {
    }

    /**
     * @param health estado observado
     * @param detail detalhe para diagnóstico (sem credenciais)
     */
    record StatusReport(Health health, String detail) {
    }

    /**
     * @param commandId      correlação entre pedido e resultado
     * @param idempotencyKey chave de idempotência enviada ao equipamento quando ele suporta
     */
    record AccessRequest(UUID commandId, String idempotencyKey) {
    }

    /**
     * Recibo de aceitação. {@code accepted=true} NÃO significa que a porta abriu.
     *
     * @param accepted o equipamento aceitou o comando
     * @param detail   detalhe para diagnóstico
     */
    record CommandReceipt(boolean accepted, String detail) {
    }

    /**
     * Resultado verificável consultado ao dispositivo (para equipamentos que expõem consulta).
     *
     * @param opened   a abertura foi observada
     * @param evidence origem da evidência
     */
    record AccessResult(boolean opened, String evidence) {
    }

    /** Falha de comunicação ou operação não suportada. */
    class DeviceCommunicationException extends Exception {
        public DeviceCommunicationException(String message) {
            super(message);
        }

        public DeviceCommunicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Identificador do adaptador, igual ao valor persistido em {@code device.adapter}. */
    String type();

    Set<Capability> capabilities();

    StatusReport getStatus(DeviceDescriptor device);

    CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request) throws DeviceCommunicationException;

    /** Consulta o resultado ao equipamento; vazio quando ele não oferece esse recurso (usa-se evento). */
    default Optional<AccessResult> getAccessResult(DeviceDescriptor device, UUID commandId) {
        return Optional.empty();
    }
}
