package br.com.domus.platform.commercial;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Suspensão de empresa e de condomínio: desliga o acesso ao app de administradores, equipe e moradores, recusa agente e
 * equipamento, e não apaga nada (a reativação devolve tudo como estava). Dados sintéticos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspensionIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID company, condoA, condoB, otherCompany, otherCondo;

    @BeforeAll
    void seed() {
        company = companies.save(new OperatorCompany("Susp Company", "susp-000000001")).getId();
        otherCompany = companies.save(new OperatorCompany("Susp Other", "susp-000000002")).getId();
        condoA = condominiums.save(new Condominium(company, "Susp A", null)).getId();
        condoB = condominiums.save(new Condominium(company, "Susp B", null)).getId();
        otherCondo = condominiums.save(new Condominium(otherCompany, "Susp Outro", null)).getId();
        Block b = blocks.save(new Block(condoA, "A"));
        UUID unit = units.save(new Unit(condoA, b.getId(), "101")).getId();
        UUID adult = residents.save(new Resident(condoA, unit, "Adulto Sintetico", null, null, ResidentType.OWNER)).getId();
        memberships.save(Membership.forResident(user("su-resident"), condoA, adult));
        memberships.save(new Membership(user("su-manager"), Role.CONDO_MANAGER, null, condoA));
        memberships.save(new Membership(user("su-operator"), Role.OPERATOR, null, condoA));
        memberships.save(new Membership(user("su-admin"), Role.COMPANY_ADMIN, company, null));
        memberships.save(new Membership(user("su-admin-other"), Role.COMPANY_ADMIN, otherCompany, null));
        memberships.save(new Membership(user("su-platform"), Role.PLATFORM_ADMIN, null, null));
    }

    private UUID user(String subject) {
        return users.save(new AppUser(subject, subject + "@example.invalid", subject)).getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String blocksUrl(UUID condo) {
        return "/api/v1/condominiums/" + condo + "/blocks";
    }

    private void everyoneOfCondoAIs(int expected) throws Exception {
        for (String who : new String[] {"su-manager", "su-operator", "su-admin"}) {
            mvc.perform(get(blocksUrl(condoA)).with(as(who))).andExpect(status().is(expected));
        }
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("su-resident")))
                .andExpect(status().is(expected));
    }

    @Test
    void suspendingTheCompanyTurnsOffEveryoneAndReactivatingBringsEverythingBack() throws Exception {
        everyoneOfCondoAIs(200);

        mvc.perform(post("/api/v1/companies/" + company + "/suspend").with(as("su-admin"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/companies/" + company + "/suspend").with(as("su-platform"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // administrador, síndico, operador e morador: acesso desligado; a outra empresa segue normal
        everyoneOfCondoAIs(403);
        mvc.perform(get("/api/v1/me/residencies").with(as("su-resident"))).andExpect(status().isForbidden());
        mvc.perform(get(blocksUrl(otherCondo)).with(as("su-admin-other"))).andExpect(status().isOk());
        // a plataforma continua vendo e reativa
        mvc.perform(get("/api/v1/companies?size=200").with(as("su-platform"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='Susp Company')].status").value("SUSPENDED"));

        mvc.perform(post("/api/v1/companies/" + company + "/reactivate").with(as("su-platform"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        everyoneOfCondoAIs(200);
        // nada foi apagado
        mvc.perform(get(blocksUrl(condoA)).with(as("su-manager"))).andExpect(jsonPath("$.items[0].name").value("A"));
    }

    @Test
    void suspendingOneCondominiumLeavesTheOthersOfTheCompanyWorking() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/suspend").with(as("su-manager"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/suspend").with(as("su-admin-other"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/suspend").with(as("su-admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(get(blocksUrl(condoA)).with(as("su-manager"))).andExpect(status().isForbidden());
        mvc.perform(get(blocksUrl(condoA)).with(as("su-admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("su-resident"))).andExpect(status().isForbidden());
        mvc.perform(get(blocksUrl(condoB)).with(as("su-admin"))).andExpect(status().isOk());
        // o administrador da empresa ainda consulta o condomínio suspenso e o reativa
        mvc.perform(get("/api/v1/condominiums/" + condoA).with(as("su-admin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(get("/api/v1/condominiums?size=200").with(as("su-platform"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='Susp A')].status").value("SUSPENDED"))
                .andExpect(jsonPath("$.items[?(@.name=='Susp Outro')]").isNotEmpty());

        mvc.perform(post("/api/v1/condominiums/" + condoA + "/reactivate").with(as("su-admin"))).andExpect(status().isOk());
        mvc.perform(get(blocksUrl(condoA)).with(as("su-manager"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("su-resident"))).andExpect(status().isOk());
    }

    @Test
    void aSuspendedCondominiumRefusesItsAgent() throws Exception {
        String created = mvc.perform(post("/api/v1/condominiums/" + condoB + "/agents").with(as("su-admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"agent-susp\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asString();
        String key = json.readTree(created).get("apiKey").asString();

        mvc.perform(get("/api/v1/agent/commands").header("X-Agent-Id", id).header("X-Agent-Key", key)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/condominiums/" + condoB + "/suspend").with(as("su-platform"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/agent/commands").header("X-Agent-Id", id).header("X-Agent-Key", key)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condoB + "/reactivate").with(as("su-platform"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/agent/commands").header("X-Agent-Id", id).header("X-Agent-Key", key)).andExpect(status().isOk());
    }
}
