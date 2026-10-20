package br.com.portaria.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decisão de entrada em contingência. Só age quando a central está inacessível e dentro do que a política assinada e
 * ainda válida permite: nada de inventar autorização, presumir sucesso da controladora ou aceitar credencial/política
 * vencida. Falha fechada: se não for possível registrar o uso, a porta não abre.
 */
public final class OfflineAccessService {

    /** Resultado da decisão. {@code reason} é OK ou o motivo da negação. */
    public record Decision(boolean granted, String reason) {
        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OfflineAccessService.class);

    private final LocalStore store;
    private final PolicyVerifier verifier;
    private final DoorController door;
    private final ConnectivityMonitor monitor;
    private final Clock clock;

    public OfflineAccessService(LocalStore store, PolicyVerifier verifier, DoorController door,
                                ConnectivityMonitor monitor, Clock clock) {
        this.store = store;
        this.verifier = verifier;
        this.door = door;
        this.monitor = monitor;
        this.clock = clock;
    }

    public Decision decide(String token, String entrance) {
        Instant now = Instant.now(clock);
        if (monitor.isOnline()) {
            return Decision.deny("DEFER_TO_CENTRAL"); // contingência só vale sem a central
        }
        Policy policy;
        try {
            Optional<LocalStore.StoredPolicy> stored = store.loadPolicy();
            if (stored.isEmpty()) {
                return Decision.deny("NO_POLICY");
            }
            policy = verifier.verify(stored.get().json(), stored.get().signature());
        } catch (LocalStore.StorageException e) {
            return Decision.deny("STORAGE_UNAVAILABLE");
        } catch (PolicyVerifier.InvalidPolicyException e) {
            log.error("SECURITY: stored policy failed verification: {}", e.getMessage());
            return Decision.deny("NO_POLICY");
        }
        if (!policy.allowOfflineEntry()) {
            return Decision.deny("OFFLINE_NOT_ALLOWED");
        }
        if (!now.isBefore(policy.validUntil())) {
            return Decision.deny("POLICY_EXPIRED");
        }
        if (entrance == null || !policy.entrances().contains(entrance)) {
            return Decision.deny("ENTRANCE_NOT_ALLOWED");
        }
        UUID id = credentialId(token);
        Policy.Credential cred = id == null ? null : policy.find(id).orElse(null);
        if (cred == null || !sigMatches(token, cred.sig())) {
            return deny("INVALID_TOKEN", null, entrance, now);
        }
        if (now.isBefore(cred.validFrom())) {
            return deny("NOT_YET_VALID", cred.id(), entrance, now);
        }
        if (!now.isBefore(cred.validUntil())) {
            return deny("EXPIRED", cred.id(), entrance, now);
        }

        OutboxEvent granted = OutboxEvent.of("OFFLINE_ENTRY_GRANTED", now, cred.id(), null, null, entrance, "OFFLINE");
        try {
            if (cred.usesSoFar() + store.localUses(cred.id()) >= cred.maxUses()) {
                return deny("EXHAUSTED", cred.id(), entrance, now);
            }
            store.reserve(granted); // se não der para registrar o uso, não abre
        } catch (LocalStore.StorageException e) {
            log.error("Cannot record offline use, denying entry: {}", e.getMessage());
            return Decision.deny("STORAGE_UNAVAILABLE");
        }
        try {
            door.open(entrance);
        } catch (DoorController.DoorException e) {
            // A controladora não confirmou: o uso reservado é devolvido e a negação fica registrada.
            rollback(granted.eventId());
            return deny("CONTROLLER_UNAVAILABLE", cred.id(), entrance, now);
        }
        try {
            store.finalizeReservation(granted.eventId());
        } catch (LocalStore.StorageException e) {
            // A porta já abriu e não conseguimos marcar o evento como pronto. Ele permanece reservado e, na próxima
            // inicialização, será reportado como INCERTO para revisão humana (o uso continua consumido).
            log.error("Door opened but event could not be finalized: {}", e.getMessage());
        }
        return new Decision(true, "OK");
    }

    private void rollback(UUID eventId) {
        try {
            store.rollbackReservation(eventId);
        } catch (LocalStore.StorageException e) {
            log.error("Could not roll back reservation {}: {}", eventId, e.getMessage());
        }
    }

    private Decision deny(String reason, UUID credentialId, String entrance, Instant now) {
        try {
            store.addReady(OutboxEvent.of("OFFLINE_ENTRY_DENIED", now, credentialId, null, null, entrance, reason));
        } catch (LocalStore.StorageException e) {
            log.warn("Could not record denial ({}): {}", reason, e.getMessage()); // melhor esforço
        }
        return Decision.deny(reason);
    }

    private static UUID credentialId(String token) {
        if (token == null || token.length() > 200) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return null;
        }
        try {
            return UUID.fromString(token.substring(0, dot));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean sigMatches(String token, String expectedSig) {
        String given = token.substring(token.indexOf('.') + 1);
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expectedSig.getBytes(StandardCharsets.UTF_8));
    }
}
