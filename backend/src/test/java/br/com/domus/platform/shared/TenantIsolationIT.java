package br.com.domus.platform.shared;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.shared.audit.AuditLogRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Isolamento entre empresas e condomínios, contra PostgreSQL real. Sem dados reais. */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired AuditLogRepository auditLog;

    static UUID alphaCompany, betaCompany, sol, lua, mar, solBlock, marBlock, solUnit, solResident;

    private void seedOnce() {
        if (alphaCompany != null) {
            return;
        }
        OperatorCompany alpha = companies.save(new OperatorCompany("Alpha", "10000000000100"));
        OperatorCompany beta = companies.save(new OperatorCompany("Beta", "20000000000200"));
        alphaCompany = alpha.getId();
        betaCompany = beta.getId();
        Condominium c1 = condominiums.save(new Condominium(alphaCompany, "Sol", null));
        Condominium c2 = condominiums.save(new Condominium(alphaCompany, "Lua", null));
        Condominium c3 = condominiums.save(new Condominium(betaCompany, "Mar", null));
        sol = c1.getId();
        lua = c2.getId();
        mar = c3.getId();
        Block b1 = blocks.save(new Block(sol, "A"));
        Block b3 = blocks.save(new Block(mar, "A"));
        solBlock = b1.getId();
        marBlock = b3.getId();
        Unit u = units.save(new Unit(sol, solBlock, "101"));
        solUnit = u.getId();
        Resident r = residents.save(new Resident(sol, solUnit, "Fulano Teste", null, null, ResidentType.OWNER));
        solResident = r.getId();

        member("platform-admin", Role.PLATFORM_ADMIN, null, null);
        member("alpha-admin", Role.COMPANY_ADMIN, alphaCompany, null);
        member("beta-admin", Role.COMPANY_ADMIN, betaCompany, null);
        member("operator-sol", Role.OPERATOR, null, sol);
        member("manager-sol", Role.CONDO_MANAGER, null, sol);
        AppUser resident = users.save(new AppUser("resident-sol", "resident-sol@example.invalid", "resident-sol"));
        memberships.save(Membership.forResident(resident.getId(), sol, solResident));
    }

    private void member(String subject, Role role, UUID company, UUID condo) {
        AppUser user = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(user.getId(), role, company, condo));
    }

    private RequestPostProcessor as(String subject) {
        seedOnce();
        return jwt().jwt(j -> j.subject(subject));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/condominiums")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/condominiums").with(as("ghost"))).andExpect(status().isForbidden());
    }

    @Test
    void companyAdminSeesOwnCondominiumsOnly() throws Exception {
        mvc.perform(get("/api/v1/condominiums").with(as("alpha-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/condominiums").with(as("beta-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void crossTenantReadIsNotFoundNotForbidden() throws Exception {
        // IDOR/BOLA: trocar o ID na URL por um condomínio de outra empresa não revela nem a existência.
        mvc.perform(get("/api/v1/condominiums/" + sol).with(as("beta-admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents").with(as("beta-admin")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents/" + solResident).with(as("beta-admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void residentIdFromAnotherCondominiumIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + lua + "/residents/" + solResident).with(as("alpha-admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorIsLimitedToAssignedCondominiums() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents").with(as("operator-sol")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/condominiums/" + lua + "/residents").with(as("operator-sol")))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorCannotManage() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + sol + "/blocks").with(as("operator-sol"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCannotReadResidents() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents").with(as("platform-admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void residentRoleHasNoAdministrativeAccess() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents").with(as("resident-sol")))
                .andExpect(status().isNotFound());
    }

    @Test
    void companyAdminCannotCreateCondominiumForAnotherCompany() throws Exception {
        mvc.perform(post("/api/v1/condominiums").with(as("alpha-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operatorCompanyId\":\"" + betaCompany + "\",\"name\":\"Invasor\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyPlatformAdminCreatesCompanies() throws Exception {
        String body = "{\"name\":\"Gamma\",\"taxId\":\"30000000000300\"}";
        mvc.perform(post("/api/v1/companies").with(as("alpha-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/companies").with(as("platform-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    @Test
    void unitCannotReferenceBlockOfAnotherCondominium() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + sol + "/units").with(as("manager-sol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockId\":\"" + marBlock + "\",\"number\":\"999\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseRejectsCrossCondominiumUnitEvenBypassingTheApi() {
        seedOnce();
        assertThatThrownBy(() -> jdbc.update(
                "insert into unit (id, condominium_id, block_id, number) values (?, ?, ?, ?)",
                UUID.randomUUID(), sol, marBlock, "X")).hasMessageContaining("violates foreign key");
    }

    @Test
    void validationErrorsReturnProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + sol + "/blocks").with(as("manager-sol"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void deniedAttemptsAreAuditedAndAuditLogIsAppendOnly() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + sol + "/residents").with(as("beta-admin")))
                .andExpect(status().isNotFound());
        assertThat(auditLog.findByCondominiumIdOrderByOccurredAtDesc(sol))
                .anyMatch(e -> e.getOutcome().equals("DENIED") && e.getAction().equals("resident.list"));
        assertThatThrownBy(() -> jdbc.update("delete from audit_log")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'x'"))
                .hasMessageContaining("append-only");
    }
}
