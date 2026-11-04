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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
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
    UUID unitId;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("IA Company", "ia-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "IA Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        unitId = unit;
        Resident r = residents.save(new Resident(condo, unit, "ia-res", null, null, ResidentType.OWNER));
        AppUser ru = users.save(new AppUser("ia-res", "ia-res@example.invalid", "ia-res"));
        memberships.save(Membership.forResident(ru.getId(), condo, r.getId()));
        AppUser mgr = users.save(new AppUser("ia-manager", "ia-manager@example.invalid", "ia-manager"));
        memberships.save(new Membership(mgr.getId(), Role.CONDO_MANAGER, null, condo));
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

    /** Atende e encerra o que sobrar na fila, para um teste não deixar chamadas que atrapalhem o seguinte. */
    private void drainQueue() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("ia-op1")));
        for (int i = 0; i < 10; i++) {
            var r = mvc.perform(post("/api/v1/operator/calls/next").with(as("ia-op1"))).andReturn().getResponse();
            if (r.getStatus() != 200 || r.getContentAsString().isBlank()) break;
            mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + json.readTree(r.getContentAsString()).get("id").asString()
                    + "/close").with(as("ia-op1")));
        }
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
    void anActionOnTheCallRenewsOnlyThatCallAndOnlyForItsOperator() throws Exception {
        drainQueue();
        String kept = ordinaryCallClaimedBy("ia-op1");
        Thread.sleep(1200);
        UUID callId = UUID.fromString(kept);
        operations.touchCall(condo, callId, UUID.randomUUID()); // outro usuário: não conta
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).minusMillis(500));
        assertThat(statusOf(kept)).isEqualTo("QUEUED");
        // o mesmo operador assume a chamada devolvida de novo (a liberação em massa devolve também as de outros testes: encerra-as)
        String again = null;
        for (int i = 0; i < 10 && !kept.equals(again); i++) {
            again = body(mvc.perform(post("/api/v1/operator/calls/next").with(as("ia-op1"))).andExpect(status().isOk()).andReturn())
                    .get("id").asString();
            if (!kept.equals(again)) {
                mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + again + "/close").with(as("ia-op1")));
            }
        }
        assertThat(again).isEqualTo(kept);
        Thread.sleep(1200);
        UUID owner = jdbc.queryForObject("select assigned_user_id from service_call where id = ?::uuid", UUID.class, again);
        operations.touchCall(condo, UUID.fromString(again), owner);
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).minusMillis(500));
        assertThat(statusOf(again)).isEqualTo("ASSIGNED");
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + again + "/close").with(as("ia-op1")));
        drainQueue();
    }

    @Test
    void openingAnIncidentOrAskingAVisitAuthorizationOnTheCallRenewsItsDeadline() throws Exception {
        drainQueue();
        String id = ordinaryCallClaimedBy("ia-op1");
        Thread.sleep(1200);
        mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents").with(as("ia-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"callId\":\"" + id + "\",\"severity\":\"LOW\",\"title\":\"Teste\"}")).andExpect(status().isCreated());
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).minusMillis(500));
        assertThat(statusOf(id)).as("a ocorrência na chamada renovou o prazo").isEqualTo("ASSIGNED");

        Thread.sleep(1200);
        mvc.perform(post("/api/v1/condominiums/" + condo + "/visit-requests").with(as("ia-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unitId + "\",\"visitorName\":\"Visitante\",\"kind\":\"VISITOR\",\"callId\":\"" + id + "\"}"))
                .andExpect(status().isCreated());
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).minusMillis(500));
        assertThat(statusOf(id)).as("o pedido de autorização renovou o prazo").isEqualTo("ASSIGNED");
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls/" + id + "/close").with(as("ia-op1")));
        drainQueue();
    }

    @Test
    void theManagerGetsAStaffNoticeWhenACallIsReturnedToTheQueue() throws Exception {
        String url = "/api/v1/condominiums/" + condo + "/staff/notifications";
        long before = body(mvc.perform(get(url + "/unread-count").with(as("ia-manager"))).andExpect(status().isOk()).andReturn()).get("unread").asLong();
        drainQueue();
        String id = ordinaryCallClaimedBy("ia-op1");
        operations.releaseInactiveCalls(Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(statusOf(id)).isEqualTo("QUEUED");
        assertThat(body(mvc.perform(get(url + "/unread-count").with(as("ia-manager"))).andReturn()).get("unread").asLong()).isEqualTo(before + 1);
        var first = body(mvc.perform(get(url).with(as("ia-manager"))).andExpect(status().isOk()).andReturn()).get("items").get(0);
        assertThat(first.get("type").asString()).isEqualTo("CALL_AUTO_RELEASED");
        assertThat(first.get("body").asString()).doesNotContain("ia-op1"); // não expõe quem era o operador
        // só o próprio síndico lê e marca como lido; o operador não vê o aviso do síndico, e o morador não tem a caixa da equipe
        String nid = first.get("id").asString();
        mvc.perform(post(url + "/" + nid + "/read").with(as("ia-op2"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(as("ia-res"))).andExpect(status().isNotFound());
        mvc.perform(post(url + "/" + nid + "/read").with(as("ia-manager"))).andExpect(status().isOk());
        assertThat(body(mvc.perform(get(url + "/unread-count").with(as("ia-manager"))).andReturn()).get("unread").asLong()).isEqualTo(before);
        assertThat(body(mvc.perform(get(url + "/unread-count").with(as("ia-op1"))).andReturn()).get("unread").asLong()).isZero();
        drainQueue();
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
