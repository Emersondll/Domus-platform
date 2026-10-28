package br.com.domus.platform.operations;

import br.com.domus.platform.AbstractIntegrationTest;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Alerta de pânico para a portaria (com a unidade de origem), filtro de encomendas e lembrete ao morador. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanicAlertAndParcelRemindIT extends AbstractIntegrationTest {

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
        OperatorCompany c = companies.save(new OperatorCompany("PA Company", "pa-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "PA Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "PA Other", null)).getId();
        Block b = blocks.save(new Block(condo, "Torre B"));
        unit1 = units.save(new Unit(condo, b.getId(), "302")).getId();
        Resident r = residents.save(new Resident(condo, unit1, "pa-res", null, null, ResidentType.OWNER));
        AppUser res = users.save(new AppUser("pa-res", "pa-res@example.invalid", "res"));
        memberships.save(Membership.forResident(res.getId(), condo, r.getId()));
        member("pa-operator", Role.OPERATOR, condo);
        member("pa-manager", Role.CONDO_MANAGER, condo);
        member("pa-other-operator", Role.OPERATOR, otherCondo);
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

    @Test
    void panicShowsUpForTheStaffOfThatCondominiumWithTheUnitAndLeavesTheQueueWhenClaimed() throws Exception {
        mvc.perform(post(base() + "/me/panic").with(as("pa-res"))).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/operator/panics").with(as("pa-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unitLabel").value("Torre B / 302"))
                .andExpect(jsonPath("$[0].condominiumName").value("PA Main"));
        mvc.perform(get("/api/v1/operator/panics").with(as("pa-manager")))
                .andExpect(jsonPath("$[0].unitLabel").value("Torre B / 302"));
        mvc.perform(get("/api/v1/operator/panics").with(as("pa-other-operator"))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/operator/panics").with(as("pa-res"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operator/panics")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/operator/shifts/start").with(as("pa-operator"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/operator/calls/next").with(as("pa-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("PANIC"))
                .andExpect(jsonPath("$.unitLabel").value("Torre B / 302"));
        mvc.perform(get("/api/v1/operator/panics").with(as("pa-operator"))).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void parcelListFiltersByStatusAndTheOperatorCanRemindTheResident() throws Exception {
        String a = register("Correios");
        String b = register("Loggi");
        mvc.perform(post(base() + "/parcels/" + a + "/pickup").with(as("pa-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickedUpByName\":\"Fulano\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(base() + "/parcels?status=RECEIVED&size=100").with(as("pa-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + b + "')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.id=='" + a + "')]").isEmpty());
        mvc.perform(get(base() + "/parcels?status=ODD").with(as("pa-operator"))).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/me/parcels?status=RECEIVED").with(as("pa-res")))
                .andExpect(jsonPath("$.items[?(@.id=='" + a + "')]").isEmpty());

        mvc.perform(post(base() + "/parcels/" + b + "/remind").with(as("pa-operator"))).andExpect(status().isOk());
        mvc.perform(get(base() + "/me/notifications").with(as("pa-res")))
                .andExpect(jsonPath("$.items[?(@.title=='Lembrete: encomenda aguardando retirada')]").isNotEmpty());
        mvc.perform(post(base() + "/parcels/" + a + "/remind").with(as("pa-operator"))).andExpect(status().isConflict());
        mvc.perform(post(base() + "/parcels/" + b + "/remind").with(as("pa-res"))).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/parcels/" + b + "/remind").with(as("pa-other-operator")))
                .andExpect(status().isNotFound());
    }

    private String register(String carrier) throws Exception {
        String res = mvc.perform(post(base() + "/parcels").with(as("pa-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":\"" + unit1 + "\",\"carrier\":\"" + carrier + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }
}
