package br.com.domus.platform;

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

/** Retorno da chamada ao morador: lista das próprias chamadas e notificação ao ser atendida e encerrada. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentCallFeedbackIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("CF Company", "cf-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "CF Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID u1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        UUID u2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        resident("cf-res-a", u1);
        resident("cf-res-a2", u1); // outro morador da mesma unidade
        resident("cf-res-b", u2);
        AppUser op = users.save(new AppUser("cf-operator", "cf-operator@example.invalid", "op"));
        memberships.save(new Membership(op.getId(), Role.OPERATOR, null, condo));
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

    @Test
    void residentSeesCallProgressAndGetsNotifiedWhenAssignedAndClosed() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("cf-operator"))).andExpect(status().isCreated());
        mvc.perform(post(base() + "/me/calls").with(as("cf-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"callerName\":null}")).andExpect(status().isCreated());

        mvc.perform(get(base() + "/me/calls").with(as("cf-res-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].assignedUserId").doesNotExist());

        String claimed = mvc.perform(post("/api/v1/operator/calls/next").with(as("cf-operator")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String callId = json.readTree(claimed).get("id").asText();

        mvc.perform(get(base() + "/me/calls").with(as("cf-res-a")))
                .andExpect(jsonPath("$.items[0].status").value("ASSIGNED"));
        mvc.perform(get(base() + "/me/notifications").with(as("cf-res-a2")))
                .andExpect(jsonPath("$.items[?(@.type=='CALL_ASSIGNED')]").isNotEmpty());

        mvc.perform(post(base() + "/calls/" + callId + "/close").with(as("cf-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"ENTRY_DENIED\",\"justification\":\"motivo interno sigiloso\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(base() + "/me/calls").with(as("cf-res-a")))
                .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[0].outcome").value("ENTRY_DENIED"));
        String notes = mvc.perform(get(base() + "/me/notifications").with(as("cf-res-a")))
                .andExpect(jsonPath("$.items[?(@.type=='CALL_CLOSED')]").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(notes).contains("não autorizada").doesNotContain("sigiloso");
    }

    @Test
    void otherUnitDoesNotSeeNorGetNotified() throws Exception {
        mvc.perform(get(base() + "/me/calls").with(as("cf-res-b")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(base() + "/me/notifications").with(as("cf-res-b")))
                .andExpect(jsonPath("$.items[?(@.type=='CALL_CLOSED')]").isEmpty());
    }

    @Test
    void nonResidentGetsNotFound() throws Exception {
        mvc.perform(get(base() + "/me/calls").with(as("cf-operator"))).andExpect(status().isNotFound());
    }
}
