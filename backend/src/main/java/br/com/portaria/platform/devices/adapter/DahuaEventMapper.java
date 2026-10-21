package br.com.portaria.platform.devices.adapter;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Traduz eventos Dahua/Intelbras para os tipos da plataforma. Regra de segurança: <b>só confirma abertura quem
 * carrega o identificador do nosso comando</b> (o {@code UserID=portaria-<commandId>} enviado no {@code openDoor}).
 * Evento sem esse identificador (cartão, botoeira, abertura manual) é registrado, mas nunca confirma comando algum;
 * se o firmware não devolver o identificador, o comando termina {@code UNKNOWN} (falha segura).
 *
 * <p>Suposições a calibrar em campo (não confirmadas em equipamento): o evento {@code AccessControl} traz o
 * {@code UserID} dentro de {@code data} e, quando há campo {@code Status}, o valor 1 indica acesso concedido; o
 * evento {@code DoorStatus} traz {@code Status} igual a {@code Open} ou {@code Close}.
 */
public final class DahuaEventMapper {

    /** Resultado da tradução. */
    public record Mapped(String type, UUID commandId) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COMMAND_REF = Pattern.compile(
            "portaria-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private DahuaEventMapper() {
    }

    public static Optional<Mapped> map(DahuaEventStream.Event event) {
        String data = event.data() == null ? "" : event.data();
        JsonNode json = parse(data);
        return switch (event.code()) {
            case "AccessControl" -> {
                if ("Stop".equals(event.action())) {
                    yield Optional.empty();
                }
                boolean granted = json == null || !json.has("Status") || json.path("Status").asInt(0) == 1;
                yield Optional.of(new Mapped(granted ? "DOOR_OPENED" : "ACCESS_DENIED", commandRef(data)));
            }
            case "DoorStatus" -> {
                String status = json == null ? "" : json.path("Status").asString("");
                yield switch (status) {
                    case "Open" -> Optional.of(new Mapped("DOOR_OPENED", null));
                    case "Close" -> Optional.of(new Mapped("DOOR_CLOSED", null));
                    default -> Optional.empty();
                };
            }
            default -> Optional.empty();
        };
    }

    private static JsonNode parse(String data) {
        if (data.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID commandRef(String data) {
        Matcher m = COMMAND_REF.matcher(data);
        return m.find() ? UUID.fromString(m.group(1)) : null;
    }
}
