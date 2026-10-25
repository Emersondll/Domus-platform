package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Lote B (fecho): mensagens individuais entre o morador e a portaria. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectMessagesIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, userA, userB;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("DM Company", "dm-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "DM Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "DM Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Block ob = blocks.save(new Block(otherCondo, "A"));
        UUID otherUnit = units.save(new Unit(otherCondo, ob.getId(), "201")).getId();
        userA = resident(condo, unit, "dm-res-a");
        userB = resident(condo, unit, "dm-res-b");
        resident(condo, unit, "dm-res-spam");
        AppUser op = users.save(new AppUser("dm-operator", "dm-operator@example.invalid", "op"));
        memberships.save(new Membership(op.getId(), Role.OPERATOR, null, condo));
        AppUser otherOp = users.save(new AppUser("dm-other-op", "dm-other-op@example.invalid", "op2"));
        memberships.save(new Membership(otherOp.getId(), Role.OPERATOR, null, otherCondo));
        resident(otherCondo, otherUnit, "dm-res-other");
    }

    private UUID resident(UUID condominium, UUID unit, String subject) {
        Resident r = residents.save(new Resident(condominium, unit, subject, null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condominium, r.getId()));
        return u.getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    private static String body(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    @Test
    void conversationFlowsBetweenResidentAndDeskAndIsPrivatePerResident() throws Exception {
        mvc.perform(post(base() + "/me/messages").with(as("dm-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content(body("Preciso liberar um prestador amanhã"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromResident").value(true));

        mvc.perform(get(base() + "/messages/threads").with(as("dm-operator")))
                .andExpect(jsonPath("$[0].residentUserId").value(userA.toString()))
                .andExpect(jsonPath("$[0].unread").value(1));
        mvc.perform(get(base() + "/messages/threads/" + userA).with(as("dm-operator")))
                .andExpect(jsonPath("$.items[0].text").value("Preciso liberar um prestador amanhã"));
        mvc.perform(get(base() + "/messages/threads").with(as("dm-operator")))
                .andExpect(jsonPath("$[0].unread").value(0));

        mvc.perform(post(base() + "/messages/threads/" + userA).with(as("dm-operator"))
                .contentType(MediaType.APPLICATION_JSON).content(body("Pode enviar o nome pelo app"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromResident").value(false));
        mvc.perform(get(base() + "/me/messages/unread").with(as("dm-res-a"))).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get(base() + "/me/notifications").with(as("dm-res-a")))
                .andExpect(jsonPath("$.items[?(@.type=='DIRECT_MESSAGE')]").isNotEmpty());
        mvc.perform(get(base() + "/me/messages").with(as("dm-res-a")))
                .andExpect(jsonPath("$.items[0].text").value("Pode enviar o nome pelo app"));
        mvc.perform(get(base() + "/me/messages/unread").with(as("dm-res-a"))).andExpect(jsonPath("$.unread").value(0));

        // Outro morador do mesmo condomínio não vê a conversa nem ganha notificação.
        mvc.perform(get(base() + "/me/messages").with(as("dm-res-b"))).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(base() + "/me/notifications").with(as("dm-res-b")))
                .andExpect(jsonPath("$.items[?(@.type=='DIRECT_MESSAGE')]").isEmpty());

        // O texto fica cifrado no banco.
        String stored = jdbc.queryForObject("SELECT text FROM direct_message WHERE condominium_id = ? "
                + "AND from_resident ORDER BY created_at LIMIT 1", String.class, condo);
        assertThat(stored).isNotEqualTo("Preciso liberar um prestador amanhã").doesNotContain("prestador");
    }

    @Test
    void tenantBoundariesAndRoles() throws Exception {
        // Operador de outro condomínio e morador de outro condomínio recebem 404.
        mvc.perform(get(base() + "/messages/threads").with(as("dm-other-op"))).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/me/messages").with(as("dm-res-other")).contentType(MediaType.APPLICATION_JSON)
                .content(body("oi"))).andExpect(status().isNotFound());
        // Morador não opera a caixa da portaria.
        mvc.perform(get(base() + "/messages/threads").with(as("dm-res-b"))).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/messages/threads/" + userB).with(as("dm-res-b"))
                .contentType(MediaType.APPLICATION_JSON).content(body("oi"))).andExpect(status().isNotFound());
        // Responder a quem não é morador do condomínio dá 404.
        mvc.perform(post(base() + "/messages/threads/" + UUID.randomUUID()).with(as("dm-operator"))
                .contentType(MediaType.APPLICATION_JSON).content(body("oi"))).andExpect(status().isNotFound());
        // Validação.
        mvc.perform(post(base() + "/me/messages").with(as("dm-res-b")).contentType(MediaType.APPLICATION_JSON)
                .content(body(" "))).andExpect(status().isBadRequest());
    }

    @Test
    void residentIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(base() + "/me/messages").with(as("dm-res-spam")).contentType(MediaType.APPLICATION_JSON)
                    .content(body("m" + i))).andExpect(status().isCreated());
        }
        mvc.perform(post(base() + "/me/messages").with(as("dm-res-spam")).contentType(MediaType.APPLICATION_JSON)
                .content(body("m11"))).andExpect(status().isTooManyRequests());
    }
}
