package br.com.domus.platform.commercial;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Correção de nome e CNPJ da empresa operadora (só plataforma) e do condomínio (plataforma ou administrador da empresa dona). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityEditIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID company, otherCompany, condoA, condoB, otherCondo;

    @BeforeAll
    void seed() {
        company = companies.save(new OperatorCompany("Ide Company", "ide-000000001")).getId();
        otherCompany = companies.save(new OperatorCompany("Ide Other", "ide-000000002")).getId();
        condoA = condominiums.save(new Condominium(company, "Ide A", "11111111000111")).getId();
        condoB = condominiums.save(new Condominium(company, "Ide B", null)).getId();
        otherCondo = condominiums.save(new Condominium(otherCompany, "Ide Outro", null)).getId();
        memberships.save(new Membership(user("ide-manager"), Role.CONDO_MANAGER, null, condoA));
        memberships.save(new Membership(user("ide-operator"), Role.OPERATOR, null, condoA));
        memberships.save(new Membership(user("ide-admin"), Role.COMPANY_ADMIN, company, null));
        memberships.save(new Membership(user("ide-admin-other"), Role.COMPANY_ADMIN, otherCompany, null));
        memberships.save(new Membership(user("ide-platform"), Role.PLATFORM_ADMIN, null, null));
    }

    private UUID user(String subject) {
        return users.save(new AppUser(subject, subject + "@example.invalid", subject)).getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private org.springframework.test.web.servlet.ResultActions putAs(String who, String url, String body) throws Exception {
        return mvc.perform(put(url).with(as(who)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<String> details(String action, UUID resource) {
        return jdbc.queryForList("select detail from audit_log where action = ? and resource_id = ? and outcome = 'SUCCESS'",
                String.class, action, resource.toString());
    }

    @Test
    void onlyThePlatformCorrectsTheCompanyAndTheAuditKeepsTheOldValue() throws Exception {
        String url = "/api/v1/companies/" + company;
        String body = "{\"name\":\"Ide Company Ltda\",\"taxId\":\"ide-000000009\"}";
        putAs("ide-admin", url, body).andExpect(status().isForbidden());
        putAs("ide-manager", url, body).andExpect(status().isForbidden());
        putAs("ide-platform", url, "{\"name\":\" \",\"taxId\":\"x\"}").andExpect(status().isBadRequest());
        putAs("ide-platform", "/api/v1/companies/" + UUID.randomUUID(), body).andExpect(status().isNotFound());
        // o CNPJ de outra empresa não pode ser usado
        putAs("ide-platform", url, "{\"name\":\"Ide Company\",\"taxId\":\"ide-000000002\"}").andExpect(status().isConflict());
        putAs("ide-platform", url, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ide Company Ltda")).andExpect(jsonPath("$.taxId").value("ide-000000009"));
        assertThat(details("company.update", company)).singleElement().asString()
                .contains("'Ide Company' -> 'Ide Company Ltda'").contains("'ide-000000001' -> 'ide-000000009'");
        // repetir o mesmo valor (o próprio CNPJ) não é conflito
        putAs("ide-platform", url, body).andExpect(status().isOk());
    }

    @Test
    void platformAndTheOwnCompanyAdminCorrectTheCondominiumAndEveryoneElseGets404() throws Exception {
        String url = "/api/v1/condominiums/" + condoA;
        putAs("ide-manager", url, "{\"name\":\"Ide A2\"}").andExpect(status().isNotFound());
        putAs("ide-operator", url, "{\"name\":\"Ide A2\"}").andExpect(status().isNotFound());
        putAs("ide-admin-other", url, "{\"name\":\"Ide A2\"}").andExpect(status().isNotFound());
        putAs("ide-admin", "/api/v1/condominiums/" + otherCondo, "{\"name\":\"Ide X\"}").andExpect(status().isNotFound());
        putAs("ide-admin", url, "{\"name\":\"\"}").andExpect(status().isBadRequest());
        // nome repetido dentro da mesma empresa
        putAs("ide-admin", url, "{\"name\":\"Ide B\"}").andExpect(status().isConflict());
        // CNPJ ausente mantém o atual; o nome muda
        putAs("ide-admin", url, "{\"name\":\"Ide A Corrigido\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ide A Corrigido"));
        assertThat(condominiums.findById(condoA).orElseThrow().getTaxId()).isEqualTo("11111111000111");
        // CNPJ novo, pela plataforma, com o valor antigo na auditoria
        putAs("ide-platform", url, "{\"name\":\"Ide A Corrigido\",\"taxId\":\"11444777000161\"}").andExpect(status().isOk());
        assertThat(condominiums.findById(condoA).orElseThrow().getTaxId()).isEqualTo("11444777000161");
        assertThat(details("condominium.update", condoA)).anyMatch(d -> d.contains("'11111111000111' -> '11444777000161'"));
        assertThat(details("condominium.update", condoA)).anyMatch(d -> d.contains("'Ide A' -> 'Ide A Corrigido'"));
    }
}
