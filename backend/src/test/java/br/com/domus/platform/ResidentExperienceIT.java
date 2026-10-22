package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.Block;
import br.com.domus.platform.commercial.BlockRepository;
import br.com.domus.platform.commercial.Condominium;
import br.com.domus.platform.commercial.CondominiumRepository;
import br.com.domus.platform.commercial.OperatorCompany;
import br.com.domus.platform.commercial.OperatorCompanyRepository;
import br.com.domus.platform.commercial.Unit;
import br.com.domus.platform.commercial.UnitRepository;
import br.com.domus.platform.identity.AppUser;
import br.com.domus.platform.identity.AppUserRepository;
import br.com.domus.platform.identity.Membership;
import br.com.domus.platform.identity.MembershipRepository;
import br.com.domus.platform.identity.Role;
import br.com.domus.platform.residents.Resident;
import br.com.domus.platform.residents.ResidentRepository;
import br.com.domus.platform.residents.ResidentType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Experiência do morador: notificações por unidade, preferências de privacidade, histórico e perfil. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentExperienceIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, unit101, unit102;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("P5 Company", "p5-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "P5 Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit101 = units.save(new Unit(condo, b.getId(), "101")).getId();
        unit102 = units.save(new Unit(condo, b.getId(), "102")).getId();
        resident("p5-res-a", unit101);
        resident("p5-res-a2", unit101); // segundo morador da mesma unidade
        resident("p5-res-b", unit102);
        AppUser op = users.save(new AppUser("p5-operator", "p5-operator@example.invalid", "op"));
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

    private int unread(String who) throws Exception {
        MvcResult r = mvc.perform(get(base() + "/me/notifications/unread-count").with(as(who))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("unread").asInt();
    }

    private void registerParcel(UUID unit) throws Exception {
        mvc.perform(post(base() + "/parcels").with(as("p5-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unit + "\",\"carrier\":\"Correios\"}")).andExpect(status().isCreated());
    }

    @Test
    void parcelNotifiesOnlyResidentsOfThatUnit() throws Exception {
        int a = unread("p5-res-a"), a2 = unread("p5-res-a2"), b = unread("p5-res-b");
        registerParcel(unit101);
        assertThat(unread("p5-res-a")).isEqualTo(a + 1);
        assertThat(unread("p5-res-a2")).isEqualTo(a2 + 1);
        assertThat(unread("p5-res-b")).isEqualTo(b);
    }

    @Test
    void preferenceTurnsParcelNotificationsOffForThatResidentOnly() throws Exception {
        mvc.perform(put(base() + "/me/preferences").with(as("p5-res-a2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parcelsEnabled\":false,\"visitorArrivalEnabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parcelsEnabled").value(false));
        int a = unread("p5-res-a"), a2 = unread("p5-res-a2");
        registerParcel(unit101);
        assertThat(unread("p5-res-a")).isEqualTo(a + 1);
        assertThat(unread("p5-res-a2")).isEqualTo(a2);
        mvc.perform(put(base() + "/me/preferences").with(as("p5-res-a2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parcelsEnabled\":true,\"visitorArrivalEnabled\":true}")).andExpect(status().isOk());
    }

    @Test
    void notificationsCanOnlyBeReadByTheirOwner() throws Exception {
        registerParcel(unit102);
        MvcResult list = mvc.perform(get(base() + "/me/notifications").with(as("p5-res-b"))).andReturn();
        String id = json.readTree(list.getResponse().getContentAsString()).get("items").get(0).get("id").asString();
        mvc.perform(post(base() + "/me/notifications/" + id + "/read").with(as("p5-res-a")))
                .andExpect(status().isNotFound());
        mvc.perform(post(base() + "/me/notifications/" + id + "/read").with(as("p5-res-b")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
    }

    @Test
    void visitorArrivalNotifiesTheHostUnitAndHistoryShowsOnlyOwnUnit() throws Exception {
        Instant now = Instant.now();
        MvcResult inv = mvc.perform(post(base() + "/me/invitations").with(as("p5-res-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorName\":\"Tia Maria\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                                + "\",\"validUntil\":\"" + now.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":1}"))
                .andExpect(status().isCreated()).andReturn();
        String token = json.readTree(inv.getResponse().getContentAsString()).get("token").asString();
        int a = unread("p5-res-a"), b = unread("p5-res-b");

        mvc.perform(post(base() + "/access/validate").with(as("p5-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"entrance\":\"portaria\"}"))
                .andExpect(jsonPath("$.granted").value(true));
        assertThat(unread("p5-res-a")).isEqualTo(a + 1);
        assertThat(unread("p5-res-b")).isEqualTo(b);

        // a segunda tentativa é negada e não gera notificação de chegada
        mvc.perform(post(base() + "/access/validate").with(as("p5-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"entrance\":\"portaria\"}"))
                .andExpect(jsonPath("$.granted").value(false));
        assertThat(unread("p5-res-a")).isEqualTo(a + 1);

        mvc.perform(get(base() + "/me/access-history").with(as("p5-res-a")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(base() + "/me/access-history").with(as("p5-res-b")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void residentUpdatesOnlyContactDataAndSeesOwnResidencies() throws Exception {
        mvc.perform(put(base() + "/me/profile").with(as("p5-res-b")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"novo@example.invalid\",\"phone\":\"+55 11 98888-7777\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("novo@example.invalid"))
                .andExpect(jsonPath("$.fullName").value("p5-res-b"));
        mvc.perform(put(base() + "/me/profile").with(as("p5-res-b")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/residencies").with(as("p5-res-b")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].condominiumName").value("P5 Main"))
                .andExpect(jsonPath("$[0].unitNumber").value("102"));
        mvc.perform(get("/api/v1/me/residencies").with(as("p5-operator"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(base() + "/me/profile").with(as("p5-operator"))).andExpect(status().isNotFound());
    }
}
