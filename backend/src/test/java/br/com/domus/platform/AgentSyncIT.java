package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.residents.entity.ResidentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lado central da contingência: identidade do agente, política assinada, sincronização idempotente e conflitos. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentSyncIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, unit;
    UUID agentId;
    String agentKey, policySecret;

    @BeforeAll
    void seed() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("P7 Company", "p7-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "P7 Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "P7 Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "p7-res", null, null, ResidentType.OWNER));
        AppUser ru = users.save(new AppUser("p7-res", "p7-res@example.invalid", "p7-res"));
        memberships.save(Membership.forResident(ru.getId(), condo, r.getId()));
        staff("p7-manager", Role.CONDO_MANAGER, condo);
        staff("p7-op", Role.OPERATOR, condo);
        staff("p7-manager-other", Role.CONDO_MANAGER, otherCondo);

        JsonNode e = enroll("agent-main");
        agentId = UUID.fromString(e.get("id").asString());
        agentKey = e.get("apiKey").asString();
        policySecret = e.get("policySecret").asString();
    }

    private void staff(String subject, Role role, UUID c) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, c));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode enroll(String name) throws Exception {
        return body(mvc.perform(post("/api/v1/condominiums/" + condo + "/agents").with(as("p7-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private MockHttpServletRequestBuilder agent(MockHttpServletRequestBuilder b, UUID id, String key) {
        return b.header("X-Agent-Id", id.toString()).header("X-Agent-Key", key);
    }

    private JsonNode invite(int maxUses, Instant from, Instant until) throws Exception {
        return body(mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations").with(as("p7-res"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"Visita\",\"kind\":\"VISITOR\",\"validFrom\":\"" + from + "\",\"validUntil\":\""
                        + until + "\",\"maxUses\":" + maxUses + "}")).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode activeInvite(int maxUses) throws Exception {
        return invite(maxUses, Instant.now().minusSeconds(60), Instant.now().plus(2, ChronoUnit.HOURS));
    }

    private JsonNode policy() throws Exception {
        MvcResult r = mvc.perform(agent(get("/api/v1/agent/policy"), agentId, agentKey)).andExpect(status().isOk()).andReturn();
        return body(r);
    }

    private JsonNode send(String eventJson) throws Exception {
        return body(mvc.perform(agent(post("/api/v1/agent/events"), agentId, agentKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[" + eventJson + "]}"))
                .andExpect(status().isOk()).andReturn()).get("results").get(0);
    }

    private String event(UUID eventId, String type, UUID credentialId, Instant at) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + at + "\",\"entrance\":\"portaria\""
                + (credentialId == null ? "" : ",\"credentialId\":\"" + credentialId + "\"") + "}";
    }

    private int conflictsOfKind(String kind) throws Exception {
        JsonNode items = body(mvc.perform(get("/api/v1/condominiums/" + condo + "/sync-conflicts").with(as("p7-manager"))).andReturn()).get("items");
        int n = 0;
        for (JsonNode c : items) {
            if (kind.equals(c.get("kind").asString())) {
                n++;
            }
        }
        return n;
    }

    private int useCountOf(String credentialId) throws Exception {
        for (JsonNode i : body(mvc.perform(get("/api/v1/condominiums/" + condo + "/me/invitations").with(as("p7-res"))).andReturn()).get("items")) {
            if (i.get("id").asString().equals(credentialId)) {
                return i.get("useCount").asInt();
            }
        }
        throw new AssertionError("credential not found");
    }

    @Test
    void onlyAManagerEnrollsAgentsAndSecretsAreShownOnce() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + condo + "/agents").with(as("p7-op")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/agents").with(as("p7-manager-other")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + condo + "/agents").with(as("p7-op")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$[0].policySecret").doesNotExist());
    }

    @Test
    void invalidRevokedAndUnknownAgentsAreRejectedIndistinguishably() throws Exception {
        mvc.perform(agent(get("/api/v1/agent/policy"), agentId, "wrong-key")).andExpect(status().isForbidden());
        mvc.perform(agent(get("/api/v1/agent/policy"), UUID.randomUUID(), agentKey)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/agent/policy")).andExpect(status().isBadRequest());
        JsonNode other = enroll("agent-to-revoke");
        UUID id = UUID.fromString(other.get("id").asString());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/agents/" + id + "/revoke").with(as("p7-manager")))
                .andExpect(status().isNoContent());
        mvc.perform(agent(get("/api/v1/agent/policy"), id, other.get("apiKey").asString())).andExpect(status().isForbidden());
    }

    @Test
    void policyIsSignedVersionedWithValidityAndListsOnlyEligibleCredentials() throws Exception {
        JsonNode inv = activeInvite(1);
        JsonNode env = policy();
        String policyJson = env.get("policy").asString();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(policySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(policyJson.getBytes(StandardCharsets.UTF_8)));
        assertThat(env.get("signature").asString()).isEqualTo(expected);

        JsonNode policy = json.readTree(policyJson);
        assertThat(policy.get("condominiumId").asString()).isEqualTo(condo.toString());
        assertThat(Instant.parse(policy.get("validUntil").asString())).isAfter(Instant.now().plus(5, ChronoUnit.HOURS));
        boolean found = false;
        for (JsonNode c : policy.get("credentials")) {
            if (c.get("id").asString().equals(inv.get("id").asString())) {
                found = true;
                assertThat(inv.get("token").asString()).endsWith("." + c.get("sig").asString());
            }
        }
        assertThat(found).isTrue();
        assertThat(policyJson).doesNotContain(inv.get("token").asString());
    }

    @Test
    void versionBumpsOnRevocationAndTheRevokedCredentialLeavesThePolicy() throws Exception {
        JsonNode inv = activeInvite(1);
        JsonNode before = json.readTree(policy().get("policy").asString());
        long version = before.get("version").asLong();
        mvc.perform(get("/api/v1/agent/policy?sinceVersion=" + version + "&ageSeconds=5").header("X-Agent-Id", agentId.toString())
                .header("X-Agent-Key", agentKey)).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations/" + inv.get("id").asString() + "/revoke").with(as("p7-res")))
                .andExpect(status().isOk());
        JsonNode after = json.readTree(policy().get("policy").asString());
        assertThat(after.get("version").asLong()).isGreaterThan(version);
        for (JsonNode c : after.get("credentials")) {
            assertThat(c.get("id").asString()).isNotEqualTo(inv.get("id").asString());
        }
        mvc.perform(get("/api/v1/agent/policy?sinceVersion=" + version + "&ageSeconds=5").header("X-Agent-Id", agentId.toString())
                .header("X-Agent-Key", agentKey)).andExpect(status().isOk());
    }

    @Test
    void duplicatedEventsAreAppliedOnlyOnce() throws Exception {
        JsonNode inv = activeInvite(3);
        String id = inv.get("id").asString();
        UUID eventId = UUID.randomUUID();
        String ev = event(eventId, "OFFLINE_ENTRY_GRANTED", UUID.fromString(id), Instant.now());
        assertThat(send(ev).get("status").asString()).isEqualTo("APPLIED");
        assertThat(send(ev).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(useCountOf(id)).isEqualTo(1);
        mvc.perform(get("/api/v1/condominiums/" + condo + "/me/access-history").with(as("p7-res")))
                .andExpect(jsonPath("$.items[?(@.reason=='OFFLINE')]").isNotEmpty());
    }

    @Test
    void revocationAfterDisconnectionIsFlaggedButRevocationBeforeIsNot() throws Exception {
        JsonNode inv = activeInvite(5);
        UUID id = UUID.fromString(inv.get("id").asString());
        Instant usedBefore = Instant.now().minusSeconds(60);
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations/" + id + "/revoke").with(as("p7-res")))
                .andExpect(status().isOk());
        int before = conflictsOfKind("REVOKED_BEFORE_USE");
        // uso ocorrido ANTES da revogação central: legítimo
        assertThat(send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", id, usedBefore)).get("status").asString())
                .isEqualTo("APPLIED");
        // uso ocorrido DEPOIS da revogação, com o agente ainda sem saber: conflito para auditoria
        JsonNode res = send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", id, Instant.now().plusSeconds(5)));
        assertThat(res.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(res.get("detail").asString()).isEqualTo("REVOKED_BEFORE_USE");
        assertThat(conflictsOfKind("REVOKED_BEFORE_USE")).isEqualTo(before + 1);
    }

    @Test
    void quotaAlreadyConsumedAtTheCentralBecomesAConflict() throws Exception {
        JsonNode inv = activeInvite(1);
        UUID id = UUID.fromString(inv.get("id").asString());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/access/validate").with(as("p7-op")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + inv.get("token").asString() + "\",\"entrance\":\"portaria\"}"))
                .andExpect(jsonPath("$.granted").value(true));
        JsonNode res = send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", id, Instant.now()));
        assertThat(res.get("status").asString()).isEqualTo("CONFLICT");
        assertThat(res.get("detail").asString()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(useCountOf(id.toString())).isEqualTo(1); // nunca passa da quota
    }

    @Test
    void unknownCredentialsExpiredUsesAndUncertainEntriesAreFlagged() throws Exception {
        assertThat(send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", UUID.randomUUID(), Instant.now())).get("detail").asString())
                .isEqualTo("UNKNOWN_CREDENTIAL");
        JsonNode inv = activeInvite(2);
        UUID id = UUID.fromString(inv.get("id").asString());
        assertThat(send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", id, Instant.now().plus(5, ChronoUnit.HOURS))).get("detail").asString())
                .isEqualTo("EXPIRED_AT_USE");
        assertThat(send(event(UUID.randomUUID(), "OFFLINE_ENTRY_UNCERTAIN", id, Instant.now())).get("detail").asString())
                .isEqualTo("UNCERTAIN_ENTRY");
        assertThat(send("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"OFFLINE_ENTRY_GRANTED\",\"occurredAt\":\"" + Instant.now() + "\"}")
                .get("status").asString()).isEqualTo("REJECTED");
    }

    @Test
    void conflictsCanBeResolvedOnlyByTheCondominiumManager() throws Exception {
        send(event(UUID.randomUUID(), "OFFLINE_ENTRY_GRANTED", UUID.randomUUID(), Instant.now()));
        JsonNode items = body(mvc.perform(get("/api/v1/condominiums/" + condo + "/sync-conflicts").with(as("p7-op"))).andReturn()).get("items");
        String conflictId = items.get(0).get("id").asString();
        mvc.perform(post("/api/v1/condominiums/" + condo + "/sync-conflicts/" + conflictId + "/resolve").with(as("p7-op")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/sync-conflicts/" + conflictId + "/resolve").with(as("p7-manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        mvc.perform(get("/api/v1/condominiums/" + condo + "/sync-conflicts").with(as("p7-manager-other")))
                .andExpect(status().isNotFound());
    }

    @Test
    void doorCommandsAreRelayedToTheAgentOnceAndConfirmedByItsEvent() throws Exception {
        JsonNode dev = body(mvc.perform(post("/api/v1/condominiums/" + condo + "/devices").with(as("p7-manager"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Portão via agente\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"AGENT_RELAY\",\"entrance\":\"portaria\",\"config\":{\"agentId\":\""
                        + agentId + "\"}}")).andExpect(status().isCreated()).andReturn());
        String deviceId = dev.get("id").asString();
        // o agente precisa estar online (visto recentemente)
        mvc.perform(agent(post("/api/v1/agent/heartbeat"), agentId, agentKey)).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p7-op")));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls").with(as("p7-op")).contentType(MediaType.APPLICATION_JSON).content("{}"));
        String call = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("p7-op"))).andReturn()).get("id").asString();
        JsonNode cmd = body(mvc.perform(post("/api/v1/condominiums/" + condo + "/devices/" + deviceId + "/open").with(as("p7-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + call + "\",\"justification\":\"Morador autorizou\",\"idempotencyKey\":\"k-relay-1\"}"))
                .andExpect(status().isAccepted()).andReturn());
        String commandId = cmd.get("id").asString();
        assertThat(cmd.get("state").asString()).isEqualTo("SENT"); // enfileirado, NÃO aberto

        JsonNode first = body(mvc.perform(agent(get("/api/v1/agent/commands"), agentId, agentKey)).andReturn());
        assertThat(first.get("commands")).hasSize(1);
        assertThat(first.get("commands").get(0).get("commandId").asString()).isEqualTo(commandId);
        assertThat(body(mvc.perform(agent(get("/api/v1/agent/commands"), agentId, agentKey)).andReturn()).get("commands")).isEmpty();

        mvc.perform(get("/api/v1/condominiums/" + condo + "/commands/" + commandId).with(as("p7-op")))
                .andExpect(jsonPath("$.state").value("SENT"));
        send("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"DOOR_OPENED\",\"occurredAt\":\"" + Instant.now()
                + "\",\"deviceId\":\"" + deviceId + "\",\"commandId\":\"" + commandId + "\"}");
        mvc.perform(get("/api/v1/condominiums/" + condo + "/commands/" + commandId).with(as("p7-op")))
                .andExpect(jsonPath("$.state").value("CONFIRMED"));

        // falha reportada pelo agente (ex.: controladora indisponível) encerra como FAILED, nunca como aberta
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls").with(as("p7-op")).contentType(MediaType.APPLICATION_JSON).content("{}"));
        String call2 = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("p7-op"))).andReturn()).get("id").asString();
        String cmd2 = body(mvc.perform(post("/api/v1/condominiums/" + condo + "/devices/" + deviceId + "/open").with(as("p7-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + call2 + "\",\"justification\":\"Segundo teste de falha\",\"idempotencyKey\":\"k-relay-2\"}")).andReturn()).get("id").asString();
        send("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"COMMAND_FAILED\",\"occurredAt\":\"" + Instant.now()
                + "\",\"deviceId\":\"" + deviceId + "\",\"commandId\":\"" + cmd2 + "\",\"reason\":\"CONTROLLER_UNAVAILABLE\"}");
        mvc.perform(get("/api/v1/condominiums/" + condo + "/commands/" + cmd2).with(as("p7-op")))
                .andExpect(jsonPath("$.state").value("FAILED")).andExpect(jsonPath("$.failureReason").value("CONTROLLER_UNAVAILABLE"));

        mvc.perform(get("/api/v1/condominiums/" + condo + "/agents").with(as("p7-op")))
                .andExpect(jsonPath("$[?(@.name=='agent-main')].online").value(true));
    }

    @Test
    void anAgentThatWasNeverSeenMakesTheRelayDeviceOffline() throws Exception {
        JsonNode silent = enroll("agent-silent");
        JsonNode dev = body(mvc.perform(post("/api/v1/condominiums/" + condo + "/devices").with(as("p7-manager"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Portão agente mudo\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"AGENT_RELAY\",\"entrance\":\"garagem\",\"config\":{\"agentId\":\""
                        + silent.get("id").asString() + "\"}}")).andReturn());
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p7-op")));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls").with(as("p7-op")).contentType(MediaType.APPLICATION_JSON).content("{}"));
        String call = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("p7-op"))).andReturn()).get("id").asString();
        mvc.perform(post("/api/v1/condominiums/" + condo + "/devices/" + dev.get("id").asString() + "/open").with(as("p7-op"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + call + "\",\"justification\":\"Agente sem contato\",\"idempotencyKey\":\"k-silent-agent\"}"))
                .andExpect(jsonPath("$.state").value("FAILED")).andExpect(jsonPath("$.failureReason").value("DEVICE_OFFLINE"));
    }

    @Test
    void rotatedKeyWorksAndTheOldOneStopsOnceTheNewOneIsUsed() throws Exception {
        JsonNode e = enroll("agent-rotate");
        UUID id = UUID.fromString(e.get("id").asString());
        String oldKey = e.get("apiKey").asString();
        String newKey = body(mvc.perform(agent(post("/api/v1/agent/rotate-key"), id, oldKey)).andExpect(status().isOk()).andReturn())
                .get("apiKey").asString();
        assertThat(newKey).isNotEqualTo(oldKey);
        mvc.perform(agent(post("/api/v1/agent/heartbeat"), id, oldKey)).andExpect(status().isNoContent()); // janela de transição
        mvc.perform(agent(post("/api/v1/agent/heartbeat"), id, newKey)).andExpect(status().isNoContent());
        mvc.perform(agent(post("/api/v1/agent/heartbeat"), id, oldKey)).andExpect(status().isForbidden());
    }
}
