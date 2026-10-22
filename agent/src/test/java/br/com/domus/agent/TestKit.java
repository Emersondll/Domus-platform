package br.com.domus.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Utilitários de teste: relógio mutável, política assinada e uma central simulada com o mesmo contrato da API real. */
final class TestKit {

    static final String SECRET = "test-policy-secret-for-the-agent";
    static final StoreCipher STORE_CIPHER = StoreCipher.fromSecret("test-store-key-0123456789-abcdefghijklmnop");
    static final UUID AGENT = UUID.randomUUID();
    static final UUID CONDO = UUID.randomUUID();

    private TestKit() {
    }

    /** Relógio que pode ser avançado pelo teste. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2030-01-01T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Credencial de teste e seu token {@code id.sig}. */
    record Cred(UUID id, String sig, Instant validFrom, Instant validUntil, int maxUses, int usesSoFar) {
        String token() {
            return id + "." + sig;
        }
    }

    static Cred cred(Clock clock, Duration from, Duration until, int maxUses) {
        Instant now = Instant.now(clock);
        return new Cred(UUID.randomUUID(), "sig-" + UUID.randomUUID(), now.plus(from), now.plus(until), maxUses, 0);
    }

    static CentralClient.PolicyEnvelope policy(Clock clock, long version, Duration ttl, UUID agentId, String secret,
                                               Cred... creds) {
        Instant now = Instant.now(clock);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("version", version);
        p.put("agentId", agentId.toString());
        p.put("condominiumId", CONDO.toString());
        p.put("issuedAt", now.toString());
        p.put("validUntil", now.plus(ttl).toString());
        p.put("allowOfflineEntry", true);
        p.put("entrances", List.of("portaria"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Cred c : creds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id().toString());
            m.put("sig", c.sig());
            m.put("validFrom", c.validFrom().toString());
            m.put("validUntil", c.validUntil().toString());
            m.put("maxUses", c.maxUses());
            m.put("usesSoFar", c.usesSoFar());
            list.add(m);
        }
        p.put("credentials", list);
        String json = Json.MAPPER.writeValueAsString(p);
        return new CentralClient.PolicyEnvelope(json, sign(json, secret));
    }

    static String sign(String json, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Central simulada. Os eventos aplicados são deduplicados por ID, como na central real. */
    static final class FakeCentral implements CentralClient {
        volatile boolean unreachable;
        volatile boolean unavailable;
        volatile boolean ackLost;
        volatile PolicyEnvelope policy;
        final List<Command> commands = new ArrayList<>();
        final List<OutboxEvent> applied = new ArrayList<>();
        private final Set<UUID> seen = new HashSet<>();

        private void gate() throws UnreachableException, UnavailableException {
            if (unreachable) {
                throw new UnreachableException("simulated network drop", null);
            }
            if (unavailable) {
                throw new UnavailableException("simulated HTTP 503");
            }
        }

        @Override
        public Optional<PolicyEnvelope> fetchPolicy(Long sinceVersion, Long ageSeconds, int backlog)
                throws UnreachableException, UnavailableException {
            gate();
            return Optional.ofNullable(policy);
        }

        @Override
        public List<EventResult> postEvents(List<OutboxEvent> events, int backlog)
                throws UnreachableException, UnavailableException {
            gate();
            List<EventResult> results = new ArrayList<>();
            for (OutboxEvent e : events) {
                if (seen.add(e.eventId())) {
                    applied.add(e);
                    results.add(new EventResult(e.eventId(), "APPLIED", null));
                } else {
                    results.add(new EventResult(e.eventId(), "DUPLICATE", null));
                }
            }
            if (ackLost) {
                throw new UnreachableException("response lost after the central applied the batch", null);
            }
            return results;
        }

        @Override
        public List<Command> fetchCommands(int backlog) throws UnreachableException, UnavailableException {
            gate();
            return new ArrayList<>(commands); // a central real entrega uma vez; aqui o teste pode repetir de propósito
        }

        @Override
        public String rotateKey() {
            return "new-key";
        }

        long appliedOfType(String type) {
            return applied.stream().filter(e -> e.type().equals(type)).count();
        }
    }

    /** Agente completo sobre um diretório temporário, reaberto para simular reinicializações. */
    static final class Rig implements AutoCloseable {
        final Path dir;
        final MutableClock clock = new MutableClock();
        final FakeCentral central = new FakeCentral();
        final SimulatedDoor door = new SimulatedDoor();
        LocalStore store;
        ConnectivityMonitor monitor;
        OfflineAccessService access;
        SyncService sync;
        CommandExecutor executor;

        Rig(Path dir) throws Exception {
            this.dir = dir;
            boot();
        }

        void boot() throws Exception {
            store = LocalStore.open(dir.resolve("agent.db"), STORE_CIPHER);
            store.recoverReservations();
            PolicyVerifier verifier = new PolicyVerifier(SECRET, AGENT);
            monitor = new ConnectivityMonitor(clock);
            access = new OfflineAccessService(store, verifier, door, monitor, clock);
            executor = new CommandExecutor(store, door, clock);
            sync = new SyncService(store, central, verifier, monitor, executor, clock);
        }

        /** Desliga e religa o agente (o relógio, a porta e a central continuam: o "mundo" não reinicia). */
        void restart() throws Exception {
            store.close();
            boot();
        }

        /** Entrega a política via um ciclo normal e deixa o agente offline em seguida. */
        void installPolicyThenGoOffline(CentralClient.PolicyEnvelope env) {
            central.policy = env;
            sync.cycle();
            central.unreachable = true;
            sync.cycle();
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
