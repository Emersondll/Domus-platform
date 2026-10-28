package br.com.domus.platform.residents;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Cadastros do domicílio: moradores (cônjuge, dependentes) e animais, pela administração e pelo morador. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HouseholdAndPetsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, unit1, unit2;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("HP Company", "hp-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "HP Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "HP Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        unit2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        resident("hp-res-1", unit1);
        resident("hp-res-2", unit2);
        AppUser mgr = users.save(new AppUser("hp-manager", "hp-manager@example.invalid", "mgr"));
        memberships.save(new Membership(mgr.getId(), Role.CONDO_MANAGER, null, condo));
        AppUser op = users.save(new AppUser("hp-operator", "hp-operator@example.invalid", "op"));
        memberships.save(new Membership(op.getId(), Role.OPERATOR, null, condo));
        AppUser otherMgr = users.save(new AppUser("hp-other-mgr", "hp-other-mgr@example.invalid", "mgr2"));
        memberships.save(new Membership(otherMgr.getId(), Role.CONDO_MANAGER, null, otherCondo));
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

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String who,
                               String body) throws Exception {
        return mvc.perform(b.with(as(who)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void managerRegistersSpouseUpdatesAndDeactivatesAndOthersCannot() throws Exception {
        String body = "{\"unitId\":\"" + unit1 + "\",\"fullName\":\"Conjuge Teste\",\"phone\":\"+55 11 90000-0001\",\"residentType\":\"SPOUSE\"}";
        send(post(base() + "/residents"), "hp-operator", body).andExpect(status().isForbidden());
        send(post(base() + "/residents"), "hp-other-mgr", body).andExpect(status().isNotFound());
        String res = send(post(base() + "/residents"), "hp-manager", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.residentType").value("SPOUSE")).andReturn().getResponse().getContentAsString();
        String id = res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get(base() + "/residents?unitId=" + unit1).with(as("hp-manager")))
                .andExpect(jsonPath("$.items[?(@.fullName=='Conjuge Teste')]").isNotEmpty());
        mvc.perform(get(base() + "/residents?unitId=" + unit2).with(as("hp-manager")))
                .andExpect(jsonPath("$.items[?(@.fullName=='Conjuge Teste')]").isEmpty());

        String upd = "{\"fullName\":\"Conjuge Corrigido\",\"residentType\":\"SPOUSE\",\"active\":false}";
        send(put(base() + "/residents/" + id), "hp-operator", upd).andExpect(status().isForbidden());
        send(put(base() + "/residents/" + id), "hp-manager", upd).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Conjuge Corrigido")).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void residentManagesOwnFamilyOnly() throws Exception {
        String spouse = "{\"fullName\":\"Esposa Casa 101\",\"relationship\":\"SPOUSE\"}";
        String r = send(post(base() + "/me/household"), "hp-res-1", spouse).andExpect(status().isCreated())
                .andExpect(jsonPath("$.removable").value(true)).andReturn().getResponse().getContentAsString();
        String id = r.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        // não dá para se cadastrar como proprietário nem como locatário
        send(post(base() + "/me/household"), "hp-res-1", "{\"fullName\":\"X\",\"relationship\":\"OWNER\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/me/household").with(as("hp-res-1")))
                .andExpect(jsonPath("$[?(@.fullName=='Esposa Casa 101')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.fullName=='hp-res-1')].removable").value(false));
        // outra unidade não vê nem remove
        mvc.perform(get(base() + "/me/household").with(as("hp-res-2")))
                .andExpect(jsonPath("$[?(@.fullName=='Esposa Casa 101')]").isEmpty());
        send(post(base() + "/me/household/" + id + "/remove"), "hp-res-2", "{}").andExpect(status().isNotFound());
        // operador não usa o autoatendimento do morador
        mvc.perform(get(base() + "/me/household").with(as("hp-operator"))).andExpect(status().isNotFound());
        send(post(base() + "/me/household/" + id + "/remove"), "hp-res-1", "{}").andExpect(status().isNoContent());
        mvc.perform(get(base() + "/me/household").with(as("hp-res-1")))
                .andExpect(jsonPath("$[?(@.fullName=='Esposa Casa 101')]").isEmpty());
    }

    @Test
    void petsByResidentAndManagerWithinTheirScope() throws Exception {
        String dog = "{\"name\":\"Rex\",\"species\":\"DOG\",\"breed\":\"Vira-lata\",\"notes\":\"sai com guia\"}";
        String r = send(post(base() + "/me/pets"), "hp-res-1", dog).andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitId").value(unit1.toString())).andReturn().getResponse().getContentAsString();
        String id = r.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        send(post(base() + "/me/pets"), "hp-res-1", "{\"name\":\"Z\",\"species\":\"DRAGON\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/me/pets").with(as("hp-res-2"))).andExpect(jsonPath("$[?(@.name=='Rex')]").isEmpty());
        send(post(base() + "/me/pets/" + id + "/remove"), "hp-res-2", "{}").andExpect(status().isNotFound());

        // administração: lista por unidade (operador lê), só o síndico cadastra
        mvc.perform(get(base() + "/pets?unitId=" + unit1).with(as("hp-operator")))
                .andExpect(jsonPath("$.items[?(@.name=='Rex')]").isNotEmpty());
        mvc.perform(get(base() + "/pets").with(as("hp-other-mgr"))).andExpect(status().isNotFound());
        String cat = "{\"unitId\":\"" + unit2 + "\",\"name\":\"Mimi\",\"species\":\"CAT\"}";
        send(post(base() + "/pets"), "hp-operator", cat).andExpect(status().isForbidden());
        send(post(base() + "/pets"), "hp-manager", cat).andExpect(status().isCreated());
        send(post(base() + "/pets"), "hp-manager", "{\"name\":\"Sem unidade\",\"species\":\"CAT\"}")
                .andExpect(status().isBadRequest());
        send(post(base() + "/me/pets/" + id + "/remove"), "hp-res-1", "{}").andExpect(status().isNoContent());
        mvc.perform(get(base() + "/me/pets").with(as("hp-res-1"))).andExpect(jsonPath("$[?(@.name=='Rex')]").isEmpty());
    }
}
