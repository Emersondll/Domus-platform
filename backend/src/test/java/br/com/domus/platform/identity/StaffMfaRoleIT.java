package br.com.domus.platform.identity;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Com a exigência ligada (padrão em produção), uma associação de equipe só vale se o token trouxer o papel do Keycloak que obriga
 * o OTP. Quem foi adicionado à equipe aqui mas esquecido lá não entra só com senha. Moradores não precisam de papel.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "portaria.security.require-staff-mfa-role=true")
class StaffMfaRoleIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, company;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Mfa Company", "mfa-000000001"));
        company = c.getId();
        condo = condominiums.save(new Condominium(company, "Mfa Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        staff("mfa-operator", Role.OPERATOR, null, condo);
        staff("mfa-manager", Role.CONDO_MANAGER, null, condo);
        staff("mfa-admin", Role.COMPANY_ADMIN, company, null);
        staff("mfa-platform", Role.PLATFORM_ADMIN, null, null);
        UUID resident = residents.save(new Resident(condo, unit, "mfa-resident", null, null, ResidentType.OWNER)).getId();
        memberships.save(Membership.forResident(user("mfa-resident"), condo, resident));
        // pessoa que é moradora e também operadora
        UUID both = residents.save(new Resident(condo, unit, "mfa-both", null, null, ResidentType.TENANT)).getId();
        UUID bothUser = user("mfa-both");
        memberships.save(Membership.forResident(bothUser, condo, both));
        memberships.save(new Membership(bothUser, Role.OPERATOR, null, condo));
    }

    private UUID user(String subject) {
        return users.save(new AppUser(subject, subject + "@example.invalid", subject)).getId();
    }

    private void staff(String subject, Role role, UUID companyId, UUID condominiumId) {
        memberships.save(new Membership(user(subject), role, companyId, condominiumId));
    }

    /** Token como o Keycloak entrega: o papel de equipe vem em realm_access.roles. */
    private static RequestPostProcessor token(String subject, String... keycloakRoles) {
        return jwt().jwt(j -> j.subject(subject).claim("realm_access", Map.of("roles", List.of(keycloakRoles))));
    }

    private static RequestPostProcessor tokenWithoutRoles(String subject) {
        return jwt().jwt(j -> j.subject(subject));
    }

    private String blocks() {
        return "/api/v1/condominiums/" + condo + "/blocks";
    }

    @Test
    void staffWithoutTheKeycloakRoleIsRefusedAndWithItIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/operator/overview").with(tokenWithoutRoles("mfa-operator"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operator/overview").with(token("mfa-operator", "offline_access"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/operator/overview").with(token("mfa-operator", "operator"))).andExpect(status().isOk());

        mvc.perform(get(blocks()).with(tokenWithoutRoles("mfa-manager"))).andExpect(status().isForbidden());
        mvc.perform(get(blocks()).with(token("mfa-manager", "condo-manager"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/me/admin-scope").with(tokenWithoutRoles("mfa-admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/admin-scope").with(token("mfa-admin", "company-admin"))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/companies").with(tokenWithoutRoles("mfa-platform"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/companies").with(token("mfa-platform", "platform-admin"))).andExpect(status().isOk());
    }

    @Test
    void theWrongStaffRoleDoesNotCount() throws Exception {
        // ter o papel de gestor no Keycloak não vale para quem é só operador aqui, e vice-versa
        mvc.perform(get("/api/v1/operator/overview").with(token("mfa-operator", "condo-manager"))).andExpect(status().isForbidden());
        mvc.perform(get(blocks()).with(token("mfa-manager", "operator"))).andExpect(status().isForbidden());
    }

    @Test
    void residentsNeedNoRoleAndKeepTheirAccessEvenWhenTheStaffPartIsRefused() throws Exception {
        mvc.perform(get("/api/v1/me/residencies").with(tokenWithoutRoles("mfa-resident"))).andExpect(status().isOk());
        // quem é morador e operador, sem o papel: continua morador, perde a parte de equipe
        mvc.perform(get("/api/v1/me/residencies").with(tokenWithoutRoles("mfa-both"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].condominiumId").value(condo.toString()));
        // a visão do operador não recusa: devolve só o que ele opera, e sem o papel não sobra condomínio nenhum
        mvc.perform(get("/api/v1/operator/overview").with(tokenWithoutRoles("mfa-both"))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/v1/operator/overview").with(token("mfa-both", "operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].condominiumId").value(condo.toString()));
        mvc.perform(get(blocks()).with(tokenWithoutRoles("mfa-both"))).andExpect(status().isNotFound());
    }

    @Test
    void theMissingRoleIsAuditedOncePerUserWindowNotOnEveryRequest() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get(blocks()).with(tokenWithoutRoles("mfa-manager"))).andExpect(status().isForbidden());
        }
        Integer rows = jdbc.queryForObject("select count(*) from audit_log a join app_user u on u.id = a.actor_user_id "
                + "where a.action = 'auth.staff-mfa-role-missing' and a.outcome = 'DENIED' and u.external_subject = 'mfa-manager'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
