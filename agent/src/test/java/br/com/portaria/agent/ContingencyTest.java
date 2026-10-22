package br.com.portaria.agent;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.portaria.agent.TestKit.Cred;
import br.com.portaria.agent.TestKit.Rig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os cenários de falha exigidos para a contingência local. */
class ContingencyTest {

    @TempDir Path dir;

    private static final Duration TTL = Duration.ofHours(6);

    private CentralClient.PolicyEnvelope policy(Rig r, long version, Cred... creds) {
        return TestKit.policy(r.clock, version, TTL, TestKit.AGENT, TestKit.SECRET, creds);
    }

    // 1. Queda de internet + 8. Retorno da conectividade
    @Test
    void internetDropAllowsEntryPerPolicyAndEventsAreFlushedWhenConnectivityReturns() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 1);
            r.central.policy = policy(r, 1, c);
            r.sync.cycle();
            assertThat(r.monitor.isOnline()).isTrue();
            assertThat(r.access.decide(c.token(), "portaria").reason()).isEqualTo("DEFER_TO_CENTRAL"); // central ok: não usa contingência

            r.central.unreachable = true;
            r.sync.cycle();
            assertThat(r.monitor.isOnline()).isFalse();
            OfflineAccessService.Decision d = r.access.decide(c.token(), "portaria");
            assertThat(d.granted()).isTrue();
            assertThat(r.door.opens()).isEqualTo(1);
            assertThat(r.store.backlog()).isEqualTo(1);
            assertThat(r.access.decide(c.token(), "portaria").reason()).isEqualTo("EXHAUSTED"); // uso único respeitado offline

            r.central.unreachable = false;
            r.central.policy = policy(r, 2); // a central agora tem outra versão
            r.sync.cycle();
            assertThat(r.monitor.isOnline()).isTrue();
            assertThat(r.central.appliedOfType("OFFLINE_ENTRY_GRANTED")).isEqualTo(1);
            assertThat(r.central.appliedOfType("OFFLINE_ENTRY_DENIED")).isEqualTo(1); // a negação também é auditada
            assertThat(r.store.backlog()).isZero();
            assertThat(r.store.loadPolicy().orElseThrow().json()).contains("\"version\":2");
        }
    }

    // 2. Indisponibilidade da API central (HTTP 503)
    @Test
    void centralApiUnavailableBehavesLikeOfflineAndKeepsEvents() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.central.policy = policy(r, 1, c);
            r.sync.cycle();
            r.central.unavailable = true;
            r.sync.cycle();
            assertThat(r.monitor.isOnline()).isFalse();
            assertThat(r.access.decide(c.token(), "portaria").granted()).isTrue();
            r.sync.cycle(); // ainda 503: nada é perdido
            assertThat(r.store.backlog()).isEqualTo(1);
            r.central.unavailable = false;
            r.sync.cycle();
            assertThat(r.store.backlog()).isZero();
        }
    }

    // 3. Reinicialização do agente
    @Test
    void restartKeepsPolicyQueueAndUsageAndReportsInterruptedEntriesAsUncertain() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 1);
            r.installPolicyThenGoOffline(policy(r, 1, c));
            assertThat(r.access.decide(c.token(), "portaria").granted()).isTrue();

            r.restart();
            assertThat(r.store.backlog()).isEqualTo(1);
            assertThat(r.access.decide(c.token(), "portaria").reason()).isEqualTo("EXHAUSTED"); // quota sobreviveu

            // queda no meio da operação: reserva gravada, porta talvez aberta, evento não finalizado
            Cred c2 = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 1);
            r.store.reserve(OutboxEvent.of("OFFLINE_ENTRY_GRANTED", java.time.Instant.now(r.clock), c2.id(), null, null, "portaria", "OFFLINE"));
            assertThat(r.store.readyBatch(10).stream().noneMatch(e -> c2.id().equals(e.credentialId()))).isTrue();
            r.restart();
            List<OutboxEvent> ready = r.store.readyBatch(10);
            assertThat(ready).anyMatch(e -> c2.id().equals(e.credentialId()) && e.type().equals("OFFLINE_ENTRY_UNCERTAIN"));
            assertThat(r.store.localUses(c2.id())).isEqualTo(1); // o uso continua consumido
        }
    }

    // 4. Perda temporária da conexão com a controladora
    @Test
    void controllerLossDeniesEntryReleasesTheUseAndNeverClaimsTheDoorOpened() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 1);
            r.installPolicyThenGoOffline(policy(r, 1, c));
            r.door.setAvailable(false);
            assertThat(r.access.decide(c.token(), "portaria").reason()).isEqualTo("CONTROLLER_UNAVAILABLE");
            assertThat(r.door.opens()).isZero();
            assertThat(r.store.localUses(c.id())).isZero(); // uso devolvido
            r.door.setAvailable(true);
            assertThat(r.access.decide(c.token(), "portaria").granted()).isTrue(); // pode tentar de novo

            // comandos da central com a controladora fora: reportado como falha, nunca como aberto
            r.central.unreachable = false;
            r.door.setAvailable(false);
            UUID command = UUID.randomUUID();
            r.central.commands.add(new CentralClient.Command(command, UUID.randomUUID(), "portaria",
                    java.time.Instant.now(r.clock).plusSeconds(60)));
            r.sync.cycle();
            assertThat(r.central.appliedOfType("COMMAND_FAILED")).isEqualTo(1);
            assertThat(r.central.appliedOfType("DOOR_OPENED")).isZero();
        }
    }

    // 5. Duplicação de eventos
    @Test
    void lostAcknowledgementsResendEventsAndTheCentralTreatsThemAsDuplicates() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.installPolicyThenGoOffline(policy(r, 1, c));
            assertThat(r.access.decide(c.token(), "portaria").granted()).isTrue();

            r.central.unreachable = false;
            r.central.ackLost = true; // a central aplica, mas a resposta se perde
            r.sync.cycle();
            assertThat(r.store.backlog()).isEqualTo(1); // o agente não sabe: mantém na fila
            assertThat(r.central.appliedOfType("OFFLINE_ENTRY_GRANTED")).isEqualTo(1);

            r.central.ackLost = false;
            r.sync.cycle();
            assertThat(r.store.backlog()).isZero();
            assertThat(r.central.appliedOfType("OFFLINE_ENTRY_GRANTED")).isEqualTo(1); // não aplicou duas vezes
        }
    }

    @Test
    void aRepeatedCommandIsExecutedAtMostOnceAndExpiredCommandsNeverRun() throws Exception {
        try (Rig r = new Rig(dir)) {
            r.central.policy = policy(r, 1);
            UUID fresh = UUID.randomUUID();
            r.central.commands.add(new CentralClient.Command(fresh, UUID.randomUUID(), "portaria",
                    java.time.Instant.now(r.clock).plusSeconds(60)));
            r.central.commands.add(new CentralClient.Command(UUID.randomUUID(), UUID.randomUUID(), "portaria",
                    java.time.Instant.now(r.clock).minusSeconds(1)));
            r.sync.cycle();
            r.sync.cycle(); // a central entrega de novo
            assertThat(r.door.opens()).isEqualTo(1);
            assertThat(r.central.appliedOfType("DOOR_OPENED")).isEqualTo(1);
            assertThat(r.central.applied).anyMatch(e -> e.type().equals("COMMAND_FAILED") && "EXPIRED".equals(e.reason()));
        }
    }

    // 6. Credencial expirada
    @Test
    void expiredAndNotYetValidCredentialsAndExpiredPoliciesAreDenied() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred expired = TestKit.cred(r.clock, Duration.ofHours(-3), Duration.ofMinutes(-1), 5);
            Cred future = TestKit.cred(r.clock, Duration.ofMinutes(30), Duration.ofHours(2), 5);
            Cred valid = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(10), 5);
            r.installPolicyThenGoOffline(policy(r, 1, expired, future, valid));
            assertThat(r.access.decide(expired.token(), "portaria").reason()).isEqualTo("EXPIRED");
            assertThat(r.access.decide(future.token(), "portaria").reason()).isEqualTo("NOT_YET_VALID");
            assertThat(r.access.decide(valid.token(), "portaria").granted()).isTrue();

            // a política vence (6h) enquanto estamos offline: nem a credencial válida abre mais
            r.clock.advance(Duration.ofHours(7));
            assertThat(r.access.decide(valid.token(), "portaria").reason()).isEqualTo("POLICY_EXPIRED");
            assertThat(r.door.opens()).isEqualTo(1);
        }
    }

    // 7. Revogação antes e depois da desconexão
    @Test
    void revocationBeforeDisconnectionIsEnforcedOfflineAndAfterIsReconciledOnReturn() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred revokedBefore = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            Cred revokedAfter = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.central.policy = policy(r, 1, revokedBefore, revokedAfter);
            r.sync.cycle();
            r.central.policy = policy(r, 2, revokedAfter); // revogada ANTES de cair: a política nova já não a tem
            r.sync.cycle();
            r.central.unreachable = true;
            r.sync.cycle();
            assertThat(r.access.decide(revokedBefore.token(), "portaria").reason()).isEqualTo("INVALID_TOKEN");

            // revogada DEPOIS da queda: o agente ainda não sabe, aceita conforme a política que tem e reporta;
            // a central detecta o conflito na reconciliação (coberto em AgentSyncIT)
            assertThat(r.access.decide(revokedAfter.token(), "portaria").granted()).isTrue();
            r.central.unreachable = false;
            r.sync.cycle();
            assertThat(r.central.appliedOfType("OFFLINE_ENTRY_GRANTED")).isEqualTo(1);
        }
    }

    // política adulterada / destinada a outro agente
    @Test
    void tamperedOrMisaddressedPoliciesAreRejectedAndTheOldOneIsKept() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.central.policy = policy(r, 1, c);
            r.sync.cycle();

            CentralClient.PolicyEnvelope good = policy(r, 2, c);
            r.central.policy = new CentralClient.PolicyEnvelope(good.policy().replace("\"maxUses\":5", "\"maxUses\":500"),
                    good.signature());
            r.sync.cycle();
            assertThat(r.store.loadPolicy().orElseThrow().json()).contains("\"version\":1");

            r.central.policy = TestKit.policy(r.clock, 3, TTL, UUID.randomUUID(), TestKit.SECRET, c); // outro agente
            r.sync.cycle();
            assertThat(r.store.loadPolicy().orElseThrow().json()).contains("\"version\":1");

            r.central.policy = TestKit.policy(r.clock, 4, TTL, TestKit.AGENT, "other-secret-not-known-to-the-agent", c);
            r.sync.cycle();
            assertThat(r.store.loadPolicy().orElseThrow().json()).contains("\"version\":1");

            r.central.policy = policy(r, 0, c); // versão antiga
            r.sync.cycle();
            assertThat(r.store.loadPolicy().orElseThrow().json()).contains("\"version\":1");
        }
    }

    @Test
    void unknownTokensWrongEntrancesAndMissingPolicyAreDenied() throws Exception {
        try (Rig r = new Rig(dir)) {
            r.central.unreachable = true;
            r.sync.cycle();
            assertThat(r.access.decide("x.y", "portaria").reason()).isEqualTo("NO_POLICY");

            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.central.unreachable = false;
            r.central.policy = policy(r, 1, c);
            r.sync.cycle();
            r.central.unreachable = true;
            r.sync.cycle();
            assertThat(r.access.decide(c.id() + ".forged", "portaria").reason()).isEqualTo("INVALID_TOKEN");
            assertThat(r.access.decide("not-a-token", "portaria").reason()).isEqualTo("INVALID_TOKEN");
            assertThat(r.access.decide(null, "portaria").reason()).isEqualTo("INVALID_TOKEN");
            assertThat(r.access.decide(c.token(), "garagem").reason()).isEqualTo("ENTRANCE_NOT_ALLOWED");
            assertThat(r.door.opens()).isZero();
        }
    }

    // 10. Corrupção ou esgotamento do armazenamento local
    @Test
    void aCorruptedStoreIsSetAsideAndRecreatedVisibly() throws Exception {
        Path file = dir.resolve("agent.db");
        Files.write(file, "this is definitely not a sqlite database, it is garbage".repeat(50).getBytes());
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            assertThat(store.recoveredFromCorruption()).isTrue();
            assertThat(store.isWritable()).isTrue();
            assertThat(store.loadPolicy()).isEmpty();
        }
        assertThat(Files.list(dir).anyMatch(p -> p.getFileName().toString().contains(".corrupt-"))).isTrue();
    }

    @Test
    void whenStorageIsExhaustedTheDoorStaysClosed() throws Exception {
        try (Rig r = new Rig(dir)) {
            Cred c = TestKit.cred(r.clock, Duration.ofMinutes(-5), Duration.ofHours(2), 5);
            r.installPolicyThenGoOffline(policy(r, 1, c));
            r.store.exhaustStorageForTesting();
            // usa o próprio armazenamento até a primeira falha de gravação (disco realmente cheio)
            boolean full = false;
            for (int i = 0; i < 10_000 && !full; i++) {
                try {
                    r.store.addReady(OutboxEvent.of("OFFLINE_ENTRY_DENIED", java.time.Instant.now(r.clock), null, null,
                            null, "portaria", "filler-" + "x".repeat(30)));
                } catch (LocalStore.StorageException e) {
                    full = true;
                }
            }
            assertThat(full).as("storage should be exhausted").isTrue();
            assertThat(r.access.decide(c.token(), "portaria").reason()).isEqualTo("STORAGE_UNAVAILABLE");
            assertThat(r.door.opens()).isZero(); // falha fechada: sem registrar o uso, não abre
        }
    }
}
