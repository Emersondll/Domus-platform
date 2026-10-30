package br.com.domus.platform.identity;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Associação do morador: código de adesão, pedido de quem ainda não tem conta e decisão do síndico. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssociationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired JdbcTemplate jdbc;

    UUID condo, otherCondo, unit, otherUnit;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("AS Company", "as-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "AS Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "AS Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Block ob = blocks.save(new Block(otherCondo, "Z"));
        otherUnit = units.save(new Unit(otherCondo, ob.getId(), "900")).getId();
        staff("as-manager", Role.CONDO_MANAGER, condo);
        staff("as-operator", Role.OPERATOR, condo);
        staff("as-other-manager", Role.CONDO_MANAGER, otherCondo);
    }

    private void staff(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
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

    private String rotate() throws Exception {
        String r = mvc.perform(post(base() + "/join-code").with(as("as-manager"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return r.replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
    }

    private static String body(String code, String name, String email, String type) {
        return "{\"code\":\"" + code + "\",\"fullName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"type\":\"" + type + "\",\"unitText\":\"Bloco A, 101\"}";
    }

    @Test
    void onlyTheManagerOfTheCondominiumHandlesTheJoinCode() throws Exception {
        mvc.perform(post(base() + "/join-code").with(as("as-operator"))).andExpect(status().isForbidden());
        mvc.perform(post(base() + "/join-code").with(as("as-other-manager"))).andExpect(status().isNotFound());
        String code = rotate();
        mvc.perform(get(base() + "/join-code").with(as("as-manager"))).andExpect(jsonPath("$.code").value(code));
        mvc.perform(get(base() + "/join-code").with(as("as-operator"))).andExpect(status().isForbidden());
    }

    @Test
    void personWithoutAccountRequestsAndManagerApproves() throws Exception {
        String code = rotate();
        send(post("/api/v1/association-requests"), "as-new-1", body("WRONGCODE1", "Ana Teste", "as-new-1@example.invalid", "OWNER"))
                .andExpect(status().isNotFound());
        send(post("/api/v1/association-requests"), "as-new-1", body(code, "Ana Teste", "as-new-1@example.invalid", "DEPENDENT"))
                .andExpect(status().isBadRequest());
        send(post("/api/v1/association-requests"), "as-new-1", body(code.toLowerCase(), "Ana Teste", "as-new-1@example.invalid", "OWNER"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.condominiumName").value("AS Main"));
        send(post("/api/v1/association-requests"), "as-new-1", body(code, "Ana Teste", "as-new-1@example.invalid", "OWNER"))
                .andExpect(status().isConflict());
        // o pedido não dá acesso nenhum
        mvc.perform(get("/api/v1/me/residencies").with(as("as-new-1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me/association-requests").with(as("as-new-1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        mvc.perform(get(base() + "/association-requests").with(as("as-operator"))).andExpect(status().isForbidden());
        mvc.perform(get(base() + "/association-requests").with(as("as-other-manager"))).andExpect(status().isNotFound());
        String listed = mvc.perform(get(base() + "/association-requests").with(as("as-manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.fullName=='Ana Teste')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = listed.replaceAll(".*\"id\":\"([^\"]+)\",\"fullName\":\"Ana Teste\".*", "$1");

        send(post(base() + "/association-requests/" + id + "/approve"), "as-manager", "{\"unitId\":\"" + otherUnit + "\"}")
                .andExpect(status().isNotFound());
        send(post(base() + "/association-requests/" + id + "/approve"), "as-operator", "{\"unitId\":\"" + unit + "\"}")
                .andExpect(status().isForbidden());
        send(post(base() + "/association-requests/" + id + "/approve"), "as-manager", "{\"unitId\":\"" + unit + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        send(post(base() + "/association-requests/" + id + "/approve"), "as-manager", "{\"unitId\":\"" + unit + "\"}")
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/me/residencies").with(as("as-new-1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].condominiumId").value(condo.toString()))
                .andExpect(jsonPath("$[0].unitNumber").value("101"));
        send(post("/api/v1/association-requests"), "as-new-1", body(code, "Ana Teste", "as-new-1@example.invalid", "OWNER"))
                .andExpect(status().isConflict());
        Integer audited = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE condominium_id = ? "
                + "AND action IN ('association.request','association.approve') AND outcome = 'SUCCESS'", Integer.class, condo);
        org.junit.jupiter.api.Assertions.assertTrue(audited != null && audited >= 2);
    }

    @Test
    void rejectedRequestCreatesNothingAndRotatingInvalidatesTheOldCode() throws Exception {
        String old = rotate();
        send(post("/api/v1/association-requests"), "as-new-2", body(old, "Beto Teste", "as-new-2@example.invalid", "TENANT"))
                .andExpect(status().isCreated());
        String id = mvc.perform(get(base() + "/association-requests").with(as("as-manager")))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\",\"fullName\":\"Beto Teste\".*", "$1");
        send(post(base() + "/association-requests/" + id + "/reject"), "as-manager", "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/v1/me/residencies").with(as("as-new-2"))).andExpect(status().isForbidden());
        send(post(base() + "/association-requests/" + id + "/reject"), "as-manager", "{}").andExpect(status().isConflict());

        String fresh = rotate();
        send(post("/api/v1/association-requests"), "as-new-3", body(old, "Caio Teste", "as-new-3@example.invalid", "OWNER"))
                .andExpect(status().isNotFound());
        mvc.perform(post(base() + "/join-code/disable").with(as("as-manager"))).andExpect(status().isNoContent());
        send(post("/api/v1/association-requests"), "as-new-3", body(fresh, "Caio Teste", "as-new-3@example.invalid", "OWNER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongCodesAreThrottledPerPerson() throws Exception {
        for (int i = 0; i < 8; i++) {
            send(post("/api/v1/association-requests"), "as-guesser", body("GUESS0000" + i, "Chute", "as-guesser@example.invalid", "OWNER"))
                    .andExpect(status().isNotFound());
        }
        send(post("/api/v1/association-requests"), "as-guesser", body("GUESS00009", "Chute", "as-guesser@example.invalid", "OWNER"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void suspendedCondominiumDoesNotAcceptRequests() throws Exception {
        String code = rotate();
        jdbc.update("UPDATE condominium SET status = 'SUSPENDED' WHERE id = ?", condo);
        try {
            send(post("/api/v1/association-requests"), "as-new-4", body(code, "Dani Teste", "as-new-4@example.invalid", "OWNER"))
                    .andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE condominium SET status = 'ACTIVE' WHERE id = ?", condo);
        }
    }
}
