package br.com.domus.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Lote B: saída de visitantes, relatório de acessos com CSV e comunicados. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessReportAndAnnouncementsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, otherCondo, unit1, unit2;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("AR Company", "ar-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "AR Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "AR Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        unit2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        Block ob = blocks.save(new Block(otherCondo, "A"));
        units.save(new Unit(otherCondo, ob.getId(), "201"));
        resident("ar-res-a", unit1);
        resident("ar-res-a2", unit1);
        resident("ar-res-b", unit2);
        AppUser op = users.save(new AppUser("ar-operator", "ar-operator@example.invalid", "op"));
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

    private JsonNode invite(String resident, String visitorName) throws Exception {
        Instant from = Instant.now();
        String res = mvc.perform(post(base() + "/me/invitations").with(as(resident))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorName\":\"" + visitorName + "\",\"kind\":\"VISITOR\",\"validFrom\":\""
                                + from + "\",\"validUntil\":\"" + from.plus(1, ChronoUnit.HOURS) + "\",\"maxUses\":2}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private String enter(String token) throws Exception {
        String res = mvc.perform(post(base() + "/access/validate").with(as("ar-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"entrance\":\"Portaria\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(true))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("attemptId").asText();
    }

    private String period() {
        return "from=" + Instant.now().minus(1, ChronoUnit.HOURS) + "&to=" + Instant.now().plus(1, ChronoUnit.HOURS);
    }

    @Test
    void visitorOnSiteLeavesOnceAndOnlyOperatorsRegisterTheExit() throws Exception {
        String attempt = enter(invite("ar-res-a", "Tia Maria").get("token").asText());

        mvc.perform(get(base() + "/access/on-site").with(as("ar-operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attempt + "')].visitorName").value("Tia Maria"))
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attempt + "')].unitLabel").value("A / 101"));

        mvc.perform(post(base() + "/access/entries/" + attempt + "/exit").with(as("ar-res-a")))
                .andExpect(status().isNotFound()); // morador não opera a portaria
        mvc.perform(post(base() + "/access/entries/" + attempt + "/exit").with(as("ar-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exitedAt").isNotEmpty());
        mvc.perform(post(base() + "/access/entries/" + attempt + "/exit").with(as("ar-operator")))
                .andExpect(status().isConflict());
        mvc.perform(get(base() + "/access/on-site").with(as("ar-operator")))
                .andExpect(jsonPath("$.items[?(@.attemptId=='" + attempt + "')]").isEmpty());
    }

    @Test
    void aDeniedEntryHasNoExit() throws Exception {
        String res = mvc.perform(post(base() + "/access/validate").with(as("ar-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"not-a-token\",\"entrance\":\"Portaria\"}"))
                .andExpect(jsonPath("$.granted").value(false)).andReturn().getResponse().getContentAsString();
        String attempt = json.readTree(res).get("attemptId").asText();
        mvc.perform(post(base() + "/access/entries/" + attempt + "/exit").with(as("ar-operator")))
                .andExpect(status().isConflict());
    }

    @Test
    void reportAndCsvAreScopedLimitedAndNeutralizeSpreadsheetFormulas() throws Exception {
        enter(invite("ar-res-b", "=HYPERLINK(\\\"http://x.invalid\\\")").get("token").asText());

        mvc.perform(get(base() + "/access/report?" + period() + "&result=GRANTED").with(as("ar-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].result").value("GRANTED"));

        String csv = mvc.perform(get(base() + "/access/report.csv?" + period()).with(as("ar-operator")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(csv).startsWith("data_hora,entrada,resultado,motivo,unidade,visitante,saida")
                .contains("\"A / 102\"").contains("\"'=HYPERLINK(").doesNotContain(",\"=HYPERLINK");

        mvc.perform(get(base() + "/access/report?from=" + Instant.now().minus(40, ChronoUnit.DAYS) + "&to=" + Instant.now())
                .with(as("ar-operator"))).andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/access/report?result=ANYTHING&" + period()).with(as("ar-operator")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/access/report.csv?" + period()).with(as("ar-res-a")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + otherCondo + "/access/report?" + period()).with(as("ar-operator")))
                .andExpect(status().isNotFound());
    }

    @Test
    void announcementsReachEveryResidentOfTheCondominiumOnly() throws Exception {
        mvc.perform(post(base() + "/announcements").with(as("ar-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Água\",\"body\":\"Sem água amanhã\"}")).andExpect(status().isNotFound());
        mvc.perform(post(base() + "/announcements").with(as("ar-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Manutenção\",\"body\":\"Caixa d'água, sexta 9h\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(base() + "/announcements").with(as("ar-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \",\"body\":\"x\"}")).andExpect(status().isBadRequest());

        for (String r : new String[] {"ar-res-a", "ar-res-b"}) {
            mvc.perform(get(base() + "/me/announcements").with(as(r)))
                    .andExpect(jsonPath("$.items[0].title").value("Manutenção"));
            mvc.perform(get(base() + "/me/notifications").with(as(r)))
                    .andExpect(jsonPath("$.items[?(@.type=='ANNOUNCEMENT')]").isNotEmpty());
        }
        mvc.perform(get(base() + "/announcements").with(as("ar-operator")))
                .andExpect(jsonPath("$.items[0].title").value("Manutenção"));
    }
}
