package br.com.domus.platform.residents;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.AbstractIntegrationTest;
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

/** Decisão do titular: só adultos usam o app (dependente fica no cadastro, sem conta); a lista de equipe mostra nomes. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdultsOnlyAppIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, unit, adultId, dependentId;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Adults Company", "adults-000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Adults Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        adultId = residents.save(new Resident(condo, unit, "Adulto Sintetico", null, null, ResidentType.SPOUSE)).getId();
        dependentId = residents.save(new Resident(condo, unit, "Dependente Sintetico", null, null, ResidentType.DEPENDENT)).getId();
        AppUser a = users.save(new AppUser("ad-adult", "ad-adult@example.invalid", "Adulto Sintetico"));
        memberships.save(Membership.forResident(a.getId(), condo, adultId));
        AppUser m = users.save(new AppUser("ad-manager", "ad-manager@example.invalid", "Sindico Sintetico"));
        memberships.save(new Membership(m.getId(), Role.CONDO_MANAGER, null, condo));
        AppUser o = users.save(new AppUser("ad-operator", "ad-operator@example.invalid", "Operador Sintetico"));
        memberships.save(new Membership(o.getId(), Role.OPERATOR, null, condo));
        users.save(new AppUser("ad-outsider", "ad-outsider@example.invalid", "Fora Sintetico"));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    @Test
    void aDependentCannotGetAnAppAccountAndAnAdultTurnedDependentLosesIt() throws Exception {
        String account = "{\"externalSubject\":\"ad-kid\",\"email\":\"ad-kid@example.invalid\",\"displayName\":\"Kid\"}";
        mvc.perform(post(base() + "/residents/" + dependentId + "/account").with(as("ad-manager"))
                .contentType(MediaType.APPLICATION_JSON).content(account))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Dependents do not have an app account; only adults use the app"));

        UUID turning = residents.save(new Resident(condo, unit, "Adulto Sintetico", null, null, ResidentType.SPOUSE)).getId();
        AppUser t = users.save(new AppUser("ad-turning", "ad-turning@example.invalid", "Adulto Sintetico"));
        memberships.save(Membership.forResident(t.getId(), condo, turning));
        mvc.perform(get(base() + "/me/invitations").with(as("ad-turning"))).andExpect(status().isOk());
        mvc.perform(put(base() + "/residents/" + turning).with(as("ad-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Adulto Sintetico\",\"residentType\":\"DEPENDENT\",\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get(base() + "/me/invitations").with(as("ad-turning"))).andExpect(status().isNotFound());
    }

    @Test
    void everyoneInTheCondominiumSeesTheStaffButOnlyTheManagerSeesResidents() throws Exception {
        mvc.perform(get(base() + "/members").with(as("ad-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.role=='OPERATOR')].displayName").value("Operador Sintetico"))
                .andExpect(jsonPath("$[?(@.role=='CONDO_MANAGER')].displayName").value("Sindico Sintetico"))
                .andExpect(jsonPath("$[?(@.role=='RESIDENT')]").isNotEmpty());
        for (String who : new String[] {"ad-operator", "ad-adult"}) {
            mvc.perform(get(base() + "/members").with(as(who))).andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.role=='OPERATOR')].displayName").value("Operador Sintetico"))
                    .andExpect(jsonPath("$[?(@.role=='CONDO_MANAGER')].displayName").value("Sindico Sintetico"))
                    .andExpect(jsonPath("$[?(@.role=='RESIDENT')]").isEmpty());
        }
        mvc.perform(get(base() + "/members").with(as("ad-outsider"))).andExpect(status().isNotFound());
    }

    @Test
    void severalAdultsOfTheSameUnitCanEachHaveAnAppAccount() throws Exception {
        UUID second = residents.save(new Resident(condo, unit, "Segundo Adulto", null, null, ResidentType.OWNER)).getId();
        mvc.perform(post(base() + "/residents/" + second + "/account").with(as("ad-manager")).contentType("application/json")
                .content("{\"externalSubject\":\"ad-second\",\"email\":\"ad-second@example.invalid\","
                        + "\"displayName\":\"Segundo Adulto\"}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get(base() + "/me/invitations").with(as("ad-second"))).andExpect(status().isOk());
        mvc.perform(get(base() + "/me/invitations").with(as("ad-adult"))).andExpect(status().isOk());
    }
}
