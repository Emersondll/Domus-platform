package br.com.domus.platform.operations;

import br.com.domus.platform.AbstractIntegrationTest;
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
import br.com.domus.platform.operations.service.OperationsService;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.time.Duration;
import java.time.Instant;
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

/** Chamada sem sinal de vida do operador volta sozinha à fila: prazo comum, prazo de pânico e sinal de vida do painel. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallInactivityReleaseIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired OperationsService operations;

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("IA Company", "ia-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "IA Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "ia-res", null, null, ResidentType.OWNER));
        AppUser ru = users.save(new AppUser("ia-res", "ia-res@example.invalid", "ia-res"));
        memberships.save(Membership.forResident(ru.getId(), condo, r.getId()));
        for (String op : new String[] {"ia-op1", "ia-op2"}) {
            AppUser u = users.save(new AppUser(op, op + "@example.invalid", op));
            memberships.save(new Membership(u.getId(), Role.OPERATOR, null, condo));
        }
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String ordinaryCallClaimedBy(String who) throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as(who)));
        String id = body(mvc.perform(post("/api/v1/condominiums/" + condo + "/calls").with(as(who))
                .contentType(MediaType.APPLICATION_JSON).content("{\"callerName\":\"Visitante\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        assertThat(body(mvc.perform(post("/api/v1/operator/calls/next").with(as(who))).andExpect(status().isOk()).andReturn())
                .get("id").asString()).isEqualTo(id);
        return id;
    }

    private String statusOf(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/condominiums/" + condo + "/calls?size=100").with(as("ia-op1")))
                .andExpect(status().isOk()).andReturn()).get("items").valueStream()
                .filter(n -> n.get("id").asString().equals(id)).findFirst().orElseThrow().get("status").asString();
    }

    @Test
    void anOrdinaryCallWithoutActivityReturnsToTheQueueAfterTheLimitAndAnotherOperatorTakesIt() throws Exception {
        String id = ordinaryCallClaimedBy("ia-op1");
        Instant now = Instant.now();

        operations.releaseInactiveCalls(now.plus(Duration.ofMinutes(4)));
        assertThat(statusOf(id)).isEqualTo("ASSIGNED"); // ainda dentro dos 5 min

        operations.releaseInactiveCalls(now.plus(Duration.ofMinutes(6)));
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        mvc.perform(get("/api/v1/condominiums/" + condo + "/calls/" + id + "/events").with(as("ia-op1")))
                .andExpect(jsonPath("$[?(@.type=='AUTO_RELEASED')].detail").isNotEmpty());
        // quem a atendia antes não a encerra mais, e outro operador a assume
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + id + "/close").with(as("ia-op1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("ia-op2")));
        mvc.perform(post("/api/v1/operator/calls/next").with(as("ia-op2")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + id + "/close").with(as("ia-op2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED\"}")).andExpect(status().isOk());
    }

    @Test
    void theHeartbeatKeepsTheOperatorsCallsAndOnlyTheirs() throws Exception {
        String kept = ordinaryCallClaimedBy("ia-op1");
        String lost = ordinaryCallClaimedBy("ia-op2");
        Thread.sleep(1200);
        mvc.perform(post("/api/v1/operator/calls/heartbeat").with(as("ia-op1"))).andExpect(status().isNoContent());
        // 5 min depois da assunção, menos meio segundo: só quem deu sinal de vida depois dela continua com a chamada
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).minusMillis(500));
        assertThat(statusOf(kept)).isEqualTo("ASSIGNED");
        assertThat(statusOf(lost)).isEqualTo("QUEUED");
        // quem não é operador não dá sinal de vida
        mvc.perform(post("/api/v1/operator/calls/heartbeat").with(as("ia-res"))).andExpect(status().isForbidden());
        for (String id : new String[] {kept, lost}) {
            mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + id + "/close").with(as(id.equals(kept) ? "ia-op1" : "ia-op2")));
        }
    }

    @Test
    void aPanicAlertIsReturnedSoonerThanAnOrdinaryCall() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("ia-op1")));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/me/panic").with(as("ia-res"))).andExpect(status().isCreated());
        String panic = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("ia-op1"))).andExpect(status().isOk()).andReturn())
                .get("id").asString();
        String ordinary = ordinaryCallClaimedBy("ia-op2");
        Instant now = Instant.now();

        operations.releaseInactiveCalls(now.plus(Duration.ofMinutes(3))); // passou dos 2 min do pânico, não dos 5 da comum
        assertThat(statusOf(panic)).isEqualTo("QUEUED");
        assertThat(statusOf(ordinary)).isEqualTo("ASSIGNED");
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + ordinary + "/close").with(as("ia-op2"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED\"}"));
        mvc.perform(post("/api/v1/operator/calls/next").with(as("ia-op1"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + panic + "/close").with(as("ia-op1"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"FALSE_ALARM\"}"));
    }
}
