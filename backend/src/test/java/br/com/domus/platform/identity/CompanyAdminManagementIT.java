package br.com.domus.platform.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.AbstractIntegrationTest;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Listar e revogar administradores de uma empresa operadora: só a plataforma, escopado por empresa, com efeito imediato. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompanyAdminManagementIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID companyA, companyB, adminAMembership, adminBMembership;

    @BeforeAll
    void seed() {
        companyA = companies.save(new OperatorCompany("CA A", "ca-a-0000000000")).getId();
        companyB = companies.save(new OperatorCompany("CA B", "ca-b-0000000000")).getId();
        user("ca-platform", Role.PLATFORM_ADMIN, null);
        adminAMembership = user("ca-admin-a", Role.COMPANY_ADMIN, companyA);
        adminBMembership = user("ca-admin-b", Role.COMPANY_ADMIN, companyB);
    }

    private UUID user(String subject, Role role, UUID company) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        return memberships.save(new Membership(u.getId(), role, company, null)).getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    @Test
    void onlyThePlatformListsAndRevokesAndOnlyWithinTheCompany() throws Exception {
        mvc.perform(get("/api/v1/companies/" + companyA + "/admins").with(as("ca-platform")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].membershipId").value(adminAMembership.toString()))
                .andExpect(jsonPath("$[0].displayName").value("ca-admin-a"));
        // o administrador da empresa (da própria ou de outra) não gerencia administradores
        mvc.perform(get("/api/v1/companies/" + companyA + "/admins").with(as("ca-admin-a"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins/" + adminAMembership + "/revoke").with(as("ca-admin-a")))
                .andExpect(status().isForbidden());
        // associação de outra empresa pelo caminho desta = 404; empresa inexistente = 404
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins/" + adminBMembership + "/revoke").with(as("ca-platform")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/" + UUID.randomUUID() + "/admins").with(as("ca-platform")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/me/admin-scope").with(as("ca-admin-a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(1));
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins/" + adminAMembership + "/revoke").with(as("ca-platform")))
                .andExpect(status().isNoContent());
        // revogar duas vezes = 404; a lista fica vazia e o ex-administrador perde o escopo na hora
        mvc.perform(post("/api/v1/companies/" + companyA + "/admins/" + adminAMembership + "/revoke").with(as("ca-platform")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/companies/" + companyA + "/admins").with(as("ca-platform")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/me/admin-scope").with(as("ca-admin-a"))).andExpect(jsonPath("$.companies.length()").value(0));
        // a outra empresa não foi afetada
        mvc.perform(get("/api/v1/companies/" + companyB + "/admins").with(as("ca-platform")))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
