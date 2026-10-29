package br.com.domus.platform.devices;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lote B: abertura remota pelo morador (opt-in) e livro de turno. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteOpenAndShiftLogIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    UUID condo, otherCondo, unit1, unit2;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("RO Company", "ro-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "RO Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "RO Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        unit2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        Block ob = blocks.save(new Block(otherCondo, "A"));
        units.save(new Unit(otherCondo, ob.getId(), "201"));
        resident("ro-res-a", unit1);
        resident("ro-res-a2", unit1);
        resident("ro-res-b", unit2);
        AppUser op = users.save(new AppUser("ro-operator", "ro-operator@example.invalid", "op"));
        memberships.save(new Membership(op.getId(), Role.OPERATOR, null, condo));
        AppUser mg = users.save(new AppUser("ro-manager", "ro-manager@example.invalid", "mg"));
        memberships.save(new Membership(mg.getId(), Role.CONDO_MANAGER, null, condo));
        AppUser oo = users.save(new AppUser("ro-op-other", "ro-op-other@example.invalid", "oo"));
        memberships.save(new Membership(oo.getId(), Role.OPERATOR, null, otherCondo));
    }

    private void resident(String subject, UUID unit) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private JsonNode device(String name, String kind) throws Exception {
        String res = mvc.perform(post(base() + "/devices").with(as("ro-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"" + kind
                                + "\",\"adapter\":\"SIMULATOR\",\"entrance\":\"main\",\"config\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private String openBody(String key) {
        return "{\"idempotencyKey\":\"" + key + "\"}";
    }

    @Test
    void remoteOpenIsOffByDefaultNeedsBothSwitchesAndIsThrottled() throws Exception {
        String door = device("Social", "ACCESS_CONTROLLER").get("id").asString();

        // desligado por padrão
        mvc.perform(get(base() + "/me/doors").with(as("ro-res-a"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k0"))).andExpect(status().isNotFound());

        // só o síndico liga a política; operador e morador não
        mvc.perform(put(base() + "/remote-open").with(as("ro-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().is4xxClientError());
        mvc.perform(put(base() + "/remote-open").with(as("ro-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isNotFound());
        mvc.perform(put(base() + "/remote-open").with(as("ro-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(jsonPath("$.enabled").value(true));

        // política ligada, mas o dispositivo ainda não foi marcado
        mvc.perform(get(base() + "/me/doors").with(as("ro-res-a"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k1"))).andExpect(status().isNotFound());

        mvc.perform(put(base() + "/devices/" + door + "/resident-openable").with(as("ro-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}"))
                .andExpect(jsonPath("$.residentOpenable").value(true));
        mvc.perform(get(base() + "/me/doors").with(as("ro-res-a"))).andExpect(jsonPath("$[0].name").value("Social"));

        String res = mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k2")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String command = json.readTree(res).get("id").asText();

        // o estado só é "CONFIRMED" com o evento do dispositivo (o simulador o emite); o morador acompanha o seu
        String state = "";
        for (int i = 0; i < 40 && !state.equals("CONFIRMED"); i++) {
            state = json.readTree(mvc.perform(get(base() + "/me/commands/" + command).with(as("ro-res-a")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("state").asText();
            Thread.sleep(100);
        }
        assertThat(state).isEqualTo("CONFIRMED");

        // mesma chave = mesmo comando (sem abrir de novo); até 5 aberturas por minuto por morador, a 6ª = 429 (V5)
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k2")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(command));
        for (String key : List.of("ro-k3", "ro-k3b", "ro-k3c", "ro-k3d")) {
            mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                    .contentType(MediaType.APPLICATION_JSON).content(openBody(key))).andExpect(status().isAccepted());
        }
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-a"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k3e")))
                .andExpect(status().isTooManyRequests());

        // outro morador e a equipe não leem o comando do morador pelo endpoint dele; a chave não vaza entre moradores
        mvc.perform(get(base() + "/me/commands/" + command).with(as("ro-res-b"))).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-b"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k2"))).andExpect(status().isConflict());
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-operator"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k4"))).andExpect(status().isNotFound());

        // desligar a política fecha tudo de novo
        mvc.perform(put(base() + "/remote-open").with(as("ro-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get(base() + "/me/doors").with(as("ro-res-b"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post(base() + "/me/doors/" + door + "/open").with(as("ro-res-b"))
                .contentType(MediaType.APPLICATION_JSON).content(openBody("ro-k5"))).andExpect(status().isNotFound());
    }

    @Test
    void onlyAccessControllersCanBeMarkedAndOtherCondominiumsAreOutOfScope() throws Exception {
        String camera = device("Câmera RO", "CAMERA").get("id").asString();
        mvc.perform(put(base() + "/devices/" + camera + "/resident-openable").with(as("ro-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/condominiums/" + otherCondo + "/remote-open").with(as("ro-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isNotFound());
    }

    @Test
    void shiftLogIsWrittenByOperatorsEncryptedAndScoped() throws Exception {
        String secret = "Morador do 101 reclamou de barulho às 23h";
        mvc.perform(post(base() + "/shift-log").with(as("ro-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NOTE\",\"text\":\"" + secret + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.text").value(secret));
        mvc.perform(post(base() + "/shift-log").with(as("ro-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"HANDOVER\",\"text\":\"Portão social com sensor instável\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post(base() + "/shift-log").with(as("ro-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NOTE\",\"text\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/shift-log").with(as("ro-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NOTE\",\"text\":\"" + "a".repeat(1001) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(base() + "/shift-log").with(as("ro-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"OTHER\",\"text\":\"x\"}")).andExpect(status().isBadRequest());

        mvc.perform(get(base() + "/shift-log").with(as("ro-manager")))
                .andExpect(jsonPath("$.items[0].kind").value("HANDOVER"))
                .andExpect(jsonPath("$.items[1].text").value(secret));
        mvc.perform(get(base() + "/shift-log").with(as("ro-res-a"))).andExpect(status().isNotFound());
        mvc.perform(get(base() + "/shift-log").with(as("ro-op-other"))).andExpect(status().isNotFound());

        String stored = jdbc.queryForObject("SELECT string_agg(text, '|') FROM shift_log_entry WHERE condominium_id = ?",
                String.class, condo);
        assertThat(stored).doesNotContain("barulho").doesNotContain("Portão");
    }
}
