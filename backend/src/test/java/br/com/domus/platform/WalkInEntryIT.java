package br.com.domus.platform;

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
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Entrada e saída de visitante autorizado na portaria (sem convite): contagem "no local" e histórico. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalkInEntryIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, unit1;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("WI Company", "wi-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "WI Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "WI Other", null)).getId();
        Block b = blocks.save(new Block(condo, "C"));
        unit1 = units.save(new Unit(condo, b.getId(), "11")).getId();
        Resident r = residents.save(new Resident(condo, unit1, "wi-res", null, null, ResidentType.OWNER));
        AppUser res = users.save(new AppUser("wi-res", "wi-res@example.invalid", "res"));
        memberships.save(Membership.forResident(res.getId(), condo, r.getId()));
        member("wi-operator", Role.OPERATOR, condo);
        member("wi-other-operator", Role.OPERATOR, otherCondo);
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

    private String request(String name, String kind) throws Exception {
        String res = mvc.perform(post(base() + "/visit-requests").with(as("wi-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":\"" + unit1 + "\",\"visitorName\":\"" + name + "\",\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private ResultActions enter(String id, String who) throws Exception {
        return mvc.perform(post(base() + "/visit-requests/" + id + "/entry").with(as(who))
                .contentType(MediaType.APPLICATION_JSON).content("{\"entrance\":\"portaria\"}"));
    }

    @Test
    void approvedWalkInEntersCountsOnSiteAndLeaves() throws Exception {
        String id = request("João (Net)", "SERVICE_PROVIDER");
        enter(id, "wi-operator").andExpect(status().isConflict()); // ainda aguardando o morador
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/approve").with(as("wi-res"))).andExpect(status().isOk());

        enter(id, "wi-res").andExpect(status().isNotFound());
        enter(id, "wi-other-operator").andExpect(status().isNotFound());
        mvc.perform(get(base() + "/visit-requests").with(as("wi-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + id + "')].entered").value(false));

        String attemptId = json.readTree(enter(id, "wi-operator").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("attemptId").asText();
        enter(id, "wi-operator").andExpect(status().isConflict()); // uma entrada por pedido

        mvc.perform(get(base() + "/visit-requests").with(as("wi-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + id + "')].entered").value(true));
        mvc.perform(get(base() + "/access/on-site").with(as("wi-operator")))
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attemptId + "')].visitorName").value("João (Net)"))
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attemptId + "')].unitLabel").value("C / 11"));
        mvc.perform(get(base() + "/me/access-history").with(as("wi-res")))
                .andExpect(jsonPath("$.items[?(@.id=='" + attemptId + "')]").isNotEmpty());
        mvc.perform(get(base() + "/me/notifications").with(as("wi-res")))
                .andExpect(jsonPath("$.items[?(@.title=='João (Net) entrou')]").isNotEmpty());

        mvc.perform(post(base() + "/access/entries/" + attemptId + "/exit").with(as("wi-operator")))
                .andExpect(status().isOk());
        mvc.perform(get(base() + "/access/on-site").with(as("wi-operator")))
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attemptId + "')]").isEmpty());
    }

    @Test
    void deniedRequestCannotEnter() throws Exception {
        String id = request("Intruso", "VISITOR");
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/deny").with(as("wi-res"))).andExpect(status().isOk());
        enter(id, "wi-operator").andExpect(status().isConflict());
    }
}
