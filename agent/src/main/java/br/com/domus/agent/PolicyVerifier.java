package br.com.domus.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/** Verifica a assinatura sobre os bytes exatos recebidos e a destinação (agente e condomínio) da política. */
final class PolicyVerifier {

    /** Política recusada: assinatura inválida, destinatário errado ou conteúdo malformado. */
    static final class InvalidPolicyException extends Exception {
        InvalidPolicyException(String message) {
            super(message);
        }
    }

    private final byte[] secret;
    private final UUID agentId;

    PolicyVerifier(String policySecret, UUID agentId) {
        this.secret = policySecret.getBytes(StandardCharsets.UTF_8);
        this.agentId = agentId;
    }

    Policy verify(String policyJson, String signature) throws InvalidPolicyException {
        byte[] expected = hmac(policyJson);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(signature == null ? "" : signature);
        } catch (IllegalArgumentException e) {
            throw new InvalidPolicyException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, given)) {
            throw new InvalidPolicyException("Signature mismatch");
        }
        try {
            JsonNode n = Json.MAPPER.readTree(policyJson);
            UUID policyAgent = UUID.fromString(n.get("agentId").asString());
            if (!policyAgent.equals(agentId)) {
                throw new InvalidPolicyException("Policy issued for another agent");
            }
            Set<String> entrances = new HashSet<>();
            n.get("entrances").forEach(e -> entrances.add(e.asString()));
            List<Policy.Credential> creds = new ArrayList<>();
            for (JsonNode c : n.get("credentials")) {
                creds.add(new Policy.Credential(UUID.fromString(c.get("id").asString()), c.get("sig").asString(),
                        Instant.parse(c.get("validFrom").asString()), Instant.parse(c.get("validUntil").asString()),
                        c.get("maxUses").asInt(), c.get("usesSoFar").asInt()));
            }
            return new Policy(n.get("version").asLong(), policyAgent, UUID.fromString(n.get("condominiumId").asString()),
                    Instant.parse(n.get("issuedAt").asString()), Instant.parse(n.get("validUntil").asString()),
                    n.get("allowOfflineEntry").asBoolean(), entrances, creds);
        } catch (InvalidPolicyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidPolicyException("Malformed policy");
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
