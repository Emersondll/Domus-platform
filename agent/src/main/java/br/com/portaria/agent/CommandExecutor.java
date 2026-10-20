package br.com.portaria.agent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executa os comandos de abertura vindos da central. Garantias: no máximo uma execução por comando (o comando é
 * marcado como visto ANTES de agir), comando vencido nunca é executado e o resultado é sempre reportado: DOOR_OPENED
 * somente se a controladora confirmou, COMMAND_FAILED caso contrário.
 */
public final class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    private final LocalStore store;
    private final DoorController door;
    private final Clock clock;

    public CommandExecutor(LocalStore store, DoorController door, Clock clock) {
        this.store = store;
        this.door = door;
        this.clock = clock;
    }

    public void execute(List<CentralClient.Command> commands) {
        for (CentralClient.Command c : commands) {
            try {
                if (!store.markCommandSeen(c.commandId())) {
                    log.info("Command {} already handled, ignoring", c.commandId());
                    continue;
                }
            } catch (LocalStore.StorageException e) {
                log.error("Cannot record command {}, refusing to execute it: {}", c.commandId(), e.getMessage());
                continue; // falha fechada: sem registro, sem execução
            }
            Instant now = Instant.now(clock);
            if (!now.isBefore(c.expiresAt())) {
                report(OutboxEvent.of("COMMAND_FAILED", now, null, c.deviceId(), c.commandId(), c.entrance(), "EXPIRED"));
                continue;
            }
            try {
                door.open(c.entrance());
                report(OutboxEvent.of("DOOR_OPENED", Instant.now(clock), null, c.deviceId(), c.commandId(),
                        c.entrance(), null));
            } catch (DoorController.DoorException e) {
                report(OutboxEvent.of("COMMAND_FAILED", Instant.now(clock), null, c.deviceId(), c.commandId(),
                        c.entrance(), "CONTROLLER_UNAVAILABLE"));
            }
        }
    }

    private void report(OutboxEvent event) {
        try {
            store.addReady(event);
        } catch (LocalStore.StorageException e) {
            // Sem como registrar o resultado: a central marcará o comando como UNKNOWN por falta de confirmação.
            log.error("Cannot record result of command {}: {}", event.commandId(), e.getMessage());
        }
    }
}
