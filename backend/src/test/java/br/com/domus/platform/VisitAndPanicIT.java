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

/** Lote A: autorização de visitante pelo morador e alerta de pânico com prioridade na fila. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisitAndPanicIT extends AbstractIntegrationTest {

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
        OperatorCompany c = companies.save(new OperatorCompany("VP Company", "vp-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "VP Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "VP Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit1 = units.save(new Unit(condo, b.getId(), "101")).getId();
        unit2 = units.save(new Unit(condo, b.getId(), "102")).getId();
        Block ob = blocks.save(new Block(otherCondo, "A"));
        units.save(new Unit(otherCondo, ob.getId(), "201"));
        resident("vp-res-a", unit1);
        resident("vp-res-a2", unit1);
        resident("vp-res-b", unit2);
        AppUser op = users.save(new AppUser("vp-operator", "vp-operator@example.invalid", "op"));
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

    private String createRequest(UUID unit) throws Exception {
        String body = "{\"unitId\":\"" + unit + "\",\"visitorName\":\"Tia Maria\",\"kind\":\"VISITOR\"}";
        String res = mvc.perform(post(base() + "/visit-requests").with(as("vp-operator"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void residentOfTheUnitApprovesOnceAndOthersCannotSeeOrAnswer() throws Exception {
        String id = createRequest(unit1);

        mvc.perform(get(base() + "/me/visit-requests").with(as("vp-res-a2")))
                .andExpect(jsonPath("$.items[0].visitorName").value("Tia Maria"))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        mvc.perform(get(base() + "/me/notifications").with(as("vp-res-a")))
                .andExpect(jsonPath("$.items[?(@.type=='VISIT_REQUEST')]").isNotEmpty());

        mvc.perform(get(base() + "/me/visit-requests").with(as("vp-res-b")))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/approve").with(as("vp-res-b")))
                .andExpect(status().isNotFound());

        mvc.perform(post(base() + "/me/visit-requests/" + id + "/approve").with(as("vp-res-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/deny").with(as("vp-res-a2")))
                .andExpect(status().isConflict());
        mvc.perform(get(base() + "/visit-requests").with(as("vp-operator")))
                .andExpect(jsonPath("$.items[?(@.id=='" + id + "')].status").value("APPROVED"));
    }

    @Test
    void residentCanDeny() throws Exception {
        String id = createRequest(unit1);
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/deny").with(as("vp-res-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DENIED"));
    }

    @Test
    void onlyOperatorsCreateRequestsAndOnlyForUnitsOfTheCondominium() throws Exception {
        String body = "{\"unitId\":\"" + unit1 + "\",\"visitorName\":\"X\",\"kind\":\"VISITOR\"}";
        mvc.perform(post(base() + "/visit-requests").with(as("vp-res-a")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound()); // fora do escopo operacional = 404
        UUID foreignUnit = units.findAll().stream().filter(u -> otherCondo.equals(u.getCondominiumId()))
                .findFirst().orElseThrow().getId();
        mvc.perform(post(base() + "/visit-requests").with(as("vp-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + foreignUnit + "\",\"visitorName\":\"X\",\"kind\":\"VISITOR\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void panicJumpsTheQueueOpensAHighIncidentAndIsNotDuplicated() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("vp-operator"))).andExpect(status().isCreated());
        // uma chamada comum mais antiga na fila
        mvc.perform(post(base() + "/me/calls").with(as("vp-res-b")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"callerName\":null}")).andExpect(status().isCreated());

        String first = mvc.perform(post(base() + "/me/panic").with(as("vp-res-a")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post(base() + "/me/panic").with(as("vp-res-a2")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json.readTree(second).get("id").asText())
                .isEqualTo(json.readTree(first).get("id").asText());

        mvc.perform(get("/api/v1/operator/overview").with(as("vp-operator")))
                .andExpect(jsonPath("$[?(@.condominiumId=='" + condo + "')].queuedPanics").value(1));
        mvc.perform(get(base() + "/incidents").with(as("vp-operator")))
                .andExpect(jsonPath("$.items[?(@.title=='Alerta de pânico')].severity").value("HIGH"));

        mvc.perform(post("/api/v1/operator/calls/next").with(as("vp-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("PANIC"));
        mvc.perform(get(base() + "/me/notifications").with(as("vp-res-a2")))
                .andExpect(jsonPath("$.items[?(@.type=='PANIC_RECEIVED')]").isNotEmpty());
    }

    @Test
    void nonResidentCannotRaisePanic() throws Exception {
        mvc.perform(post(base() + "/me/panic").with(as("vp-operator"))).andExpect(status().isNotFound());
    }
}
