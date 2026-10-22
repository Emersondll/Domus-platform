package br.com.portaria.agent;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agente local de contingência. Configuração por variáveis de ambiente (ver docs/agent.md). A controladora real é um
 * adaptador {@link DoorController} específico do fabricante; esta versão usa {@link SimulatedDoor} e NÃO deve ser
 * usada em produção sem esse adaptador.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        AgentConfig cfg = AgentConfig.fromEnv();
        Clock clock = Clock.systemUTC();
        LocalStore store = LocalStore.open(cfg.dataDir().resolve("agent.db"), StoreCipher.fromSecret(cfg.storeKey()));
        if (store.recoveredFromCorruption()) {
            log.error("Local store was corrupted and has been recreated; unsynchronized events may have been lost");
        }
        int recovered = store.recoverReservations();
        if (recovered > 0) {
            log.warn("{} reservation(s) interrupted by a restart will be reported as UNCERTAIN", recovered);
        }

        // A chave pode ter sido rotacionada: o valor persistido tem precedência sobre o ambiente.
        AtomicReference<String> apiKey = new AtomicReference<>(store.meta("apiKey").orElse(cfg.apiKey()));
        CentralClient central = new HttpCentralClient(cfg.centralUrl(), cfg.agentId(), apiKey::get, cfg.requestTimeout());

        if (args.length > 0 && args[0].equals("--rotate-key")) {
            String newKey = central.rotateKey();
            store.putMeta("apiKey", newKey); // persiste antes de passar a usar
            log.info("Agent key rotated and stored locally");
            return;
        }

        DoorController door = new SimulatedDoor();
        PolicyVerifier verifier = new PolicyVerifier(cfg.policySecret(), cfg.agentId());
        ConnectivityMonitor monitor = new ConnectivityMonitor(clock);
        OfflineAccessService access = new OfflineAccessService(store, verifier, door, monitor, clock);
        CommandExecutor executor = new CommandExecutor(store, door, clock);
        SyncService sync = new SyncService(store, central, verifier, monitor, executor, clock);
        LocalApi api = new LocalApi(cfg.localPort(), cfg.localKey(), access, store, monitor);
        api.start();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(sync::cycle, 0, cfg.syncInterval().toSeconds(), TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            api.close();
            store.close();
        }));
        log.info("Agent {} started; local API on 127.0.0.1:{}, data in {}", cfg.agentId(), api.port(), Path.of(cfg.dataDir().toString()));
    }

    private Main() {
    }
}
