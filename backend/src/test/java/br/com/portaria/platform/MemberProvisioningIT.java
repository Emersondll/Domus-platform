package br.com.portaria.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.portaria.platform.commercial.Block;
import br.com.portaria.platform.commercial.BlockRepository;
import br.com.portaria.platform.commercial.Condominium;
import br.com.portaria.platform.commercial.CondominiumRepository;
import br.com.portaria.platform.commercial.OperatorCompany;
import br.com.portaria.platform.commercial.OperatorCompanyRepository;
import br.com.portaria.platform.commercial.Unit;
import br.com.portaria.platform.commercial.UnitRepository;
import br.com.portaria.platform.identity.AppUser;
import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.Membership;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import br.com.portaria.platform.residents.Resident;
import br.com.portaria.platform.residents.ResidentRepository;
import br.com.portaria.platform.residents.ResidentType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/** Provisionamento de acessos: quem pode criar quais papéis, efeito imediato e isolamento entre empresas. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemberProvisioningIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID companyA, companyB, condoA, condoB, residentA;

    @BeforeAll
    void seed() {
        companyA = companies.save(new OperatorCompany("P9 A", "p9-a-0000000000")).getId();
        companyB = companies.save(new OperatorCompany("P9 B", "p9-b-0000000000")).getId();
        condoA = condominiums.save(new Condominium(companyA, "P9 CondoA", null)).getId();
        condoB = condominiums.save(new Condominium(companyB, "P9 CondoB", null)).getId();
        Block b = blocks.save(new Block(condoA, "A"));
        UUID unit = units.save(new Unit(condoA, b.getId(), "101")).getId();
        residentA = residents.save(new Resident(condoA, unit, "Morador P9", null, null, ResidentType.OWNER)).getId();
        user("p9-platform", Role.PLATFORM_ADMIN, null, null);
        user("p9-admin-a", Role.COMPANY_ADMIN, companyA, null);
        user("p9-admin-b", Role.COMPANY_ADMIN, companyB, null);
        user("p9-manager-a", Role.CONDO_MANAGER, null, condoA);
        user("p9-operator-a", Role.OPERATOR, null, condoA);
    }

    private void user(String subject, Role role, UUID company, UUID condo) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, company, condo));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String account(String subject) {
        return "{\"externalSubject\":\"" + subject + "\",\"email\":\"" + subject + "@example.invalid\",\"displayName\":\"" + subject + "\"}";
    }

    private String staff(String role, String subject) {
        return "{\"role\":\"" + role + "\",\"account\":" + account(subject) + "}";
    }

    @Test
    void onlyThePlatformCreatesCompanyAdmins() throws Exception {
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(account("p9-new-admin"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins").with(as("p9-platform")).contentType(MediaType.APPLICATION_JSON)
                .content(account("p9-new-admin"))).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("COMPANY_ADMIN"));
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins").with(as("p9-platform")).contentType(MediaType.APPLICATION_JSON)
                .content(account("p9-new-admin"))).andExpect(status().isConflict());
        // o novo administrador já enxerga os condomínios da empresa
        mvc.perform(get("/api/v1/condominiums").with(as("p9-new-admin"))).andExpect(jsonPath("$.items[0].name").value("P9 CondoA"));
    }

    @Test
    void companyAdminAssignsOperatorsAndManagersOfItsOwnCondominiumsOnly() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-op-new"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-op-new"))).andExpect(status().isConflict());
        // operador multicondomínio: o mesmo usuário em outro condomínio da MESMA empresa
        UUID condoA2 = condominiums.save(new Condominium(companyA, "P9 CondoA2", null)).getId();
        mvc.perform(post("/api/v1/condominiums/" + condoA2 + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-op-new"))).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-b")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-evil"))).andExpect(status().isNotFound());
        // síndico e operador não atribuem papéis de equipe
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-manager-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-x"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-operator-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("CONDO_MANAGER", "p9-y"))).andExpect(status().isForbidden());
        // papéis fora da lista são recusados
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("COMPANY_ADMIN", "p9-z"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("PLATFORM_ADMIN", "p9-z"))).andExpect(status().isBadRequest());
    }

    @Test
    void revokingAMembershipTakesEffectImmediately() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content(staff("OPERATOR", "p9-revoke-me"))).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asString();
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/residents").with(as("p9-revoke-me"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + id + "/revoke").with(as("p9-manager-a")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + id + "/revoke").with(as("p9-admin-a")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/residents").with(as("p9-revoke-me"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/members/" + id + "/revoke").with(as("p9-admin-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void managerLinksAResidentAccountOnceAndTheResidentCanThenUseTheApp() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/residents/" + residentA + "/account").with(as("p9-operator-a"))
                .contentType(MediaType.APPLICATION_JSON).content(account("p9-resident"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/residents/" + residentA + "/account").with(as("p9-manager-a"))
                .contentType(MediaType.APPLICATION_JSON).content(account("p9-resident"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RESIDENT"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/residents/" + residentA + "/account").with(as("p9-manager-a"))
                .contentType(MediaType.APPLICATION_JSON).content(account("p9-resident-2"))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/me/residencies").with(as("p9-resident"))).andExpect(jsonPath("$[0].condominiumName").value("P9 CondoA"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/residents/" + residentA + "/account").with(as("p9-admin-b"))
                .contentType(MediaType.APPLICATION_JSON).content(account("p9-intruder"))).andExpect(status().isNotFound());
    }

    @Test
    void memberListIsRestrictedToManagement() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/members").with(as("p9-manager-a"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/members").with(as("p9-operator-a"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/members").with(as("p9-admin-b"))).andExpect(status().isNotFound());
    }
}
