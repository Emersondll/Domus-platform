package br.com.domus.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento pendente de sincronização. O {@code eventId} é gerado aqui e torna o reenvio idempotente na central.
 *
 * @param eventId      identificador único
 * @param type         OFFLINE_ENTRY_GRANTED, OFFLINE_ENTRY_DENIED, OFFLINE_ENTRY_UNCERTAIN, DOOR_OPENED, COMMAND_FAILED
 * @param occurredAt   instante em que ocorreu
 * @param credentialId credencial envolvida
 * @param deviceId     dispositivo envolvido
 * @param commandId    comando ao qual responde
 * @param entrance     entrada
 * @param reason       motivo
 */
public record OutboxEvent(UUID eventId, String type, Instant occurredAt, UUID credentialId, UUID deviceId,
                          UUID commandId, String entrance, String reason) {

    static OutboxEvent of(String type, Instant at, UUID credentialId, UUID deviceId, UUID commandId, String entrance,
                          String reason) {
        return new OutboxEvent(UUID.randomUUID(), type, at, credentialId, deviceId, commandId, entrance, reason);
    }

    /** Representação JSON usada na fila local e no envio à central (datas e IDs como texto ISO/UUID). */
    java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("eventId", eventId.toString());
        m.put("type", type);
        m.put("occurredAt", occurredAt.toString());
        m.put("credentialId", credentialId == null ? null : credentialId.toString());
        m.put("deviceId", deviceId == null ? null : deviceId.toString());
        m.put("commandId", commandId == null ? null : commandId.toString());
        m.put("entrance", entrance);
        m.put("reason", reason);
        return m;
    }

    /** Eventos que consomem uso de credencial (contam contra a quota local até serem sincronizados). */
    boolean consumesUse() {
        return "OFFLINE_ENTRY_GRANTED".equals(type) || "OFFLINE_ENTRY_UNCERTAIN".equals(type);
    }
}
