package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.privacy.service.RetentionService;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** "Meus acessos": identidade de acesso, registro de passagens (sentido, referência desconhecida) e visão só do titular. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentAccessIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionService retention;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, ana, bia, outsider;
    String deviceIn, deviceInKey, deviceUnset, deviceUnsetKey, deviceOut, deviceOutKey;

    @BeforeAll
    void seed() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("RA Company", "ra-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "RA Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "RA Other", null)).getId();
        Block b = blocks.save(new Block(condo, "R"));
        UUID unit = units.save(new Unit(condo, b.getId(), "21")).getId();
        Block ob = blocks.save(new Block(otherCondo, "R"));
        UUID otherUnit = units.save(new Unit(otherCondo, ob.getId(), "22")).getId();
        ana = resident("ra-ana", condo, unit);
        bia = resident("ra-bia", condo, unit); // mesma unidade: não vê os acessos da Ana
        outsider = resident("ra-outsider", otherCondo, otherUnit);
        member("ra-manager", Role.CONDO_MANAGER, condo);
        member("ra-operator", Role.OPERATOR, condo);
        member("ra-other-manager", Role.CONDO_MANAGER, otherCondo);
        String[] in = device("Leitor entrada", "{\"direction\":\"IN\"}");
        deviceIn = in[0];
        deviceInKey = in[1];
        String[] unset = device("Leitor unico", "{}");
        deviceUnset = unset[0];
        deviceUnsetKey = unset[1];
        String[] out = device("Leitor saida", "{\"direction\":\"OUT\"}");
        deviceOut = out[0];
        deviceOutKey = out[1];
        identity(ana, "CARD", "ra-card-ana");
        identity(bia, "TERMINAL_USER", "ra-user-bia");
    }

    private UUID resident(String subject, UUID condominium, UUID unit) {
        Resident r = residents.save(new Resident(condominium, unit, subject, null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condominium, r.getId()));
        return r.getId();
    }

    private void member(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private String[] device(String name, String config) throws Exception {
        String res = mvc.perform(post(base() + "/devices").with(as("ra-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"SIMULATOR\","
                                + "\"entrance\":\"portaria\",\"config\":" + config + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(res);
        return new String[] {n.get("id").asText(), n.get("apiKey").asText()};
    }

    private String identity(UUID resident, String kind, String ref) throws Exception {
        String res = mvc.perform(post(base() + "/residents/" + resident + "/access-identities").with(as("ra-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"" + kind + "\",\"ref\":\"" + ref + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private ResultActions event(String device, String key, String type, String ref, String direction) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"occurredAt\":\"" + Instant.now() + "\""
                + (ref == null ? "" : ",\"identityRef\":\"" + ref + "\"")
                + (direction == null ? "" : ",\"direction\":\"" + direction + "\"") + "}";
        return mvc.perform(post("/api/v1/devices/" + device + "/events").header("X-Device-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode mine(String who, UUID condominium) throws Exception {
        String res = mvc.perform(get("/api/v1/condominiums/" + condominium + "/me/accesses").with(as(who)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void residentSeesOnlyOwnPassagesWithDirectionFromTheAccessPoint() throws Exception {
        event(deviceIn, deviceInKey, "DOOR_OPENED", "RA-CARD-ANA", null).andExpect(status().isAccepted());
        event(deviceOut, deviceOutKey, "DOOR_OPENED", "ra-card-ana", "IN").andExpect(status().isAccepted()); // sentido do ponto prevalece
        event(deviceUnset, deviceUnsetKey, "DOOR_OPENED", "ra-card-ana", null).andExpect(status().isAccepted());
        event(deviceIn, deviceInKey, "DOOR_OPENED", "ra-user-bia", null).andExpect(status().isAccepted());

        JsonNode ana1 = mine("ra-ana", condo);
        // Outros testes da classe também registram passagens: aqui se verifica o que é da Ana e só da Ana.
        assertThat(ana1.get("totalElements").asInt()).isGreaterThanOrEqualTo(3);
        java.util.Set<String> directions = new java.util.HashSet<>();
        ana1.get("items").forEach(i -> {
            directions.add(i.get("direction").asString());
            assertThat(i.get("method").asString()).isEqualTo("CARD"); // nenhuma passagem da Bia (usuário do terminal)
        });
        assertThat(directions).contains("IN", "OUT", "UNKNOWN");

        JsonNode bia1 = mine("ra-bia", condo);
        assertThat(bia1.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        bia1.get("items").forEach(i -> assertThat(i.get("method").asString()).isEqualTo("TERMINAL_USER"));
        assertThat(mine("ra-outsider", otherCondo).get("totalElements").asInt()).isZero();
    }

    @Test
    void staffAndOtherCondominiumsCannotReadThePersonalHistory() throws Exception {
        for (String who : new String[] {"ra-manager", "ra-operator", "ra-other-manager", "ra-outsider"}) {
            mvc.perform(get(base() + "/me/accesses").with(as(who))).andExpect(status().isNotFound());
            mvc.perform(get(base() + "/me/accesses/last").with(as(who))).andExpect(status().isNotFound());
            mvc.perform(get(base() + "/me/accesses/policy").with(as(who))).andExpect(status().isNotFound());
        }
        mvc.perform(get(base() + "/me/accesses/policy").with(as("ra-bia"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionDays").value(90));
        mvc.perform(get(base() + "/me/accesses")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownReferenceLeavesNoPersonalRecordAndWrongKeyIsRefused() throws Exception {
        long before = jdbc.queryForObject("select count(*) from resident_access_event", Long.class);
        event(deviceIn, deviceInKey, "DOOR_OPENED", "REF-QUE-NAO-EXISTE", null).andExpect(status().isAccepted());
        event(deviceIn, deviceInKey, "DOOR_OPENED", null, null).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from resident_access_event", Long.class)).isEqualTo(before);
        event(deviceIn, "chave-errada", "DOOR_OPENED", "ra-card-ana", null).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from resident_access_event", Long.class)).isEqualTo(before);
    }

    @Test
    void deniedPassageIsRecordedAsDeniedAndLastReturnsTheNewest() throws Exception {
        event(deviceUnset, deviceUnsetKey, "ACCESS_DENIED", "ra-user-bia", null).andExpect(status().isAccepted());
        mvc.perform(get(base() + "/me/accesses/last").with(as("ra-bia")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.last.result").value("DENIED"))
                .andExpect(jsonPath("$.last.direction").value("UNKNOWN"));
        // Quem ainda não tem registro recebe last = null (estado vazio honesto).
        mvc.perform(get("/api/v1/condominiums/" + otherCondo + "/me/accesses/last").with(as("ra-outsider")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.last").doesNotExist());
    }

    @Test
    void periodIsLimitedAndEachQueryIsAudited() throws Exception {
        Instant now = Instant.now();
        mvc.perform(get(base() + "/me/accesses").with(as("ra-ana")).param("from", now.minus(40, ChronoUnit.DAYS).toString())
                .param("to", now.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/me/accesses").with(as("ra-ana")).param("from", now.toString())
                .param("to", now.minus(1, ChronoUnit.DAYS).toString())).andExpect(status().isBadRequest());
        long before = jdbc.queryForObject("select count(*) from audit_log where action = 'access.history.view.self'", Long.class);
        mine("ra-ana", condo);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'access.history.view.self'",
                Long.class)).isEqualTo(before + 1);
    }

    @Test
    void identityRegistrationIsRestrictedUniqueEncryptedAndRevocable() throws Exception {
        String url = base() + "/residents/" + ana + "/access-identities";
        String body = "{\"kind\":\"CARD\",\"ref\":\"ra-card-extra\"}";
        mvc.perform(post(url).with(as("ra-operator")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(as("ra-other-manager")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post(url).with(as("ra-ana")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        String res = mvc.perform(post(url).with(as("ra-manager")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ref").doesNotExist())
                .andExpect(jsonPath("$.external_ref").doesNotExist()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(res).get("id").asString();
        // a mesma referência não pode pertencer a duas pessoas
        mvc.perform(post(base() + "/residents/" + bia + "/access-identities").with(as("ra-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post(url).with(as("ra-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"FACE_REF\",\"ref\":\"x\"}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select external_ref from access_identity where id = ?::uuid", String.class, id))
                .startsWith("enc:v1:").doesNotContain("extra");

        long before = mine("ra-ana", condo).get("totalElements").asInt();
        event(deviceIn, deviceInKey, "DOOR_OPENED", "ra-card-extra", null).andExpect(status().isAccepted());
        assertThat(mine("ra-ana", condo).get("totalElements").asInt()).isEqualTo(before + 1);
        mvc.perform(post(url + "/" + id + "/revoke").with(as("ra-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        event(deviceIn, deviceInKey, "DOOR_OPENED", "ra-card-extra", null).andExpect(status().isAccepted());
        assertThat(mine("ra-ana", condo).get("totalElements").asInt()).isEqualTo(before + 1);
    }

    @Test
    void deviceDirectionMustBeInOrOut() throws Exception {
        mvc.perform(post(base() + "/devices").with(as("ra-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Leitor ruim\",\"kind\":\"ACCESS_CONTROLLER\",\"adapter\":\"SIMULATOR\","
                                + "\"entrance\":\"portaria\",\"config\":{\"direction\":\"SIDEWAYS\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retentionRemovesOldPassagesAndTheDataSubjectRightsCoverThem() throws Exception {
        UUID unit = units.save(new Unit(condo, blocks.save(new Block(condo, "Z")).getId(), "99")).getId();
        UUID zed = resident("ra-zed", condo, unit);
        identity(zed, "CARD", "ra-card-zed");
        event(deviceIn, deviceInKey, "DOOR_OPENED", "ra-card-zed", null).andExpect(status().isAccepted());
        event(deviceIn, deviceInKey, "DOOR_OPENED", "ra-card-zed", null).andExpect(status().isAccepted());
        jdbc.update("update resident_access_event set occurred_at = now() - interval '200 days' where resident_id = ?"
                + " and id = (select id from resident_access_event where resident_id = ? limit 1)", zed, zed);
        retention.purge();
        assertThat(jdbc.queryForObject("select count(*) from resident_access_event where resident_id = ?", Long.class, zed))
                .isEqualTo(1);

        String exported = mvc.perform(get(base() + "/residents/" + zed + "/export").with(as("ra-manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessHistory.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(exported).doesNotContain("ra-card-zed");
        mvc.perform(post(base() + "/residents/" + zed + "/anonymize").with(as("ra-manager"))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from resident_access_event where resident_id = ?", Long.class, zed))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from access_identity where resident_id = ?", Long.class, zed))
                .isZero();
    }
}
