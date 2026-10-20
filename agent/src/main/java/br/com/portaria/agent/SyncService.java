package br.com.portaria.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ciclo de sincronização com a central: (1) envia a fila de eventos, (2) renova a política (verificando a assinatura),
 * (3) busca e executa comandos. Nunca lança exceção para o agendador: qualquer falha de rede apenas marca o agente
 * como offline, mantendo tudo na fila local para a próxima tentativa.
 */
public final class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final int BATCH = 100;
    private static final Set<String> FINAL = Set.of("APPLIED", "DUPLICATE", "CONFLICT", "REJECTED");

    private final LocalStore store;
    private final CentralClient central;
    private final PolicyVerifier verifier;
    private final ConnectivityMonitor monitor;
    private final CommandExecutor commands;
    private final Clock clock;

    public SyncService(LocalStore store, CentralClient central, PolicyVerifier verifier, ConnectivityMonitor monitor,
                       CommandExecutor commands, Clock clock) {
        this.store = store;
        this.central = central;
        this.verifier = verifier;
        this.monitor = monitor;
        this.commands = commands;
        this.clock = clock;
    }

    public void cycle() {
        try {
            flushOutbox();
            refreshPolicy();
            List<CentralClient.Command> pending = central.fetchCommands(backlog());
            monitor.success();
            if (!pending.isEmpty()) {
                commands.execute(pending);
                flushOutbox();
            }
        } catch (CentralClient.UnreachableException | CentralClient.UnavailableException e) {
            monitor.failure(e.getMessage());
        } catch (CentralClient.AuthException e) {
            monitor.failure("credentials rejected by central");
            log.error("SECURITY: {}", e.getMessage());
        } catch (LocalStore.StorageException e) {
            log.error("Local storage failure during sync: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected sync failure", e);
        }
    }

    /** Envia eventos em lotes; só apaga localmente o que a central confirmou (APPLIED/DUPLICATE/CONFLICT/REJECTED). */
    void flushOutbox() throws CentralClient.UnreachableException, CentralClient.UnavailableException,
            CentralClient.AuthException, LocalStore.StorageException {
        while (true) {
            List<OutboxEvent> batch = store.readyBatch(BATCH);
            if (batch.isEmpty()) {
                return;
            }
            List<CentralClient.EventResult> results = central.postEvents(batch, backlog());
            monitor.success();
            Set<UUID> acked = results.stream().filter(r -> FINAL.contains(r.status()))
                    .map(CentralClient.EventResult::eventId).collect(Collectors.toSet());
            results.stream().filter(r -> "CONFLICT".equals(r.status()) || "REJECTED".equals(r.status()))
                    .forEach(r -> log.warn("Central reported {} for event {}: {}", r.status(), r.eventId(), r.detail()));
            store.deleteEvents(acked);
            if (acked.isEmpty()) {
                return; // nada confirmado: evita laço infinito
            }
        }
    }

    private void refreshPolicy() throws CentralClient.UnreachableException, CentralClient.UnavailableException,
            CentralClient.AuthException, LocalStore.StorageException {
        Optional<Policy> current = currentPolicy();
        Long since = current.map(Policy::version).orElse(null);
        Long age = current.map(p -> Duration.between(p.issuedAt(), Instant.now(clock)).getSeconds()).orElse(null);
        Optional<CentralClient.PolicyEnvelope> env = central.fetchPolicy(since, age, backlog());
        monitor.success();
        if (env.isEmpty()) {
            return;
        }
        try {
            Policy incoming = verifier.verify(env.get().policy(), env.get().signature());
            if (current.isPresent() && incoming.version() < current.get().version()) {
                log.warn("Ignoring older policy version {} (have {})", incoming.version(), current.get().version());
                return;
            }
            store.savePolicy(env.get().policy(), env.get().signature());
            log.info("Policy v{} stored, valid until {}", incoming.version(), incoming.validUntil());
        } catch (PolicyVerifier.InvalidPolicyException e) {
            log.error("SECURITY: rejected policy from central: {}", e.getMessage());
        }
    }

    private Optional<Policy> currentPolicy() throws LocalStore.StorageException {
        Optional<LocalStore.StoredPolicy> stored = store.loadPolicy();
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(verifier.verify(stored.get().json(), stored.get().signature()));
        } catch (PolicyVerifier.InvalidPolicyException e) {
            return Optional.empty();
        }
    }

    private int backlog() {
        try {
            return store.backlog();
        } catch (LocalStore.StorageException e) {
            return 0;
        }
    }
}
