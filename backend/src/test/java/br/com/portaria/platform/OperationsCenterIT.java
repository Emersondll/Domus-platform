package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.portaria.platform.commercial.Block;
import br.com.portaria.platform.commercial.BlockRepository;
import br.com.portaria.platform.commercial.Condominium;
import br.com.portaria.platform.commercial.CondominiumRepository;
import br.com.portaria.platform.commercial.OperatorCompany;
import br.com.portaria.platform.commercial.OperatorCompanyRepository;
import br.com.portaria.platform.commercial.Unit;
import br.com.portaria.platform.commercial.UnitRepository;
import br.com.portaria.platform.identity.AppUser;
import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.Membership;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import br.com.portaria.platform.residents.Resident;
import br.com.portaria.platform.residents.ResidentRepository;
import br.com.portaria.platform.residents.ResidentType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Central de operações: turnos, fila concorrente, transferência, justificativas, incidentes e isolamento. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsCenterIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, other, queueCondo, unit, op1Id, op2Id;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("P4 Company", "p4-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "P4 Main", null)).getId();
        other = condominiums.save(new Condominium(c.getId(), "P4 Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "p4-res", null, null, ResidentType.OWNER));
        AppUser ru = users.save(new AppUser("p4-res", "p4-res@example.invalid", "p4-res"));
        memberships.save(Membership.forResident(ru.getId(), condo, r.getId()));
        op1Id = operator("p4-op1", condo);
        op2Id = operator("p4-op2", condo);
        queueCondo = condominiums.save(new Condominium(c.getId(), "P4 Queue", null)).getId();
        operator("p4-q1", queueCondo);
        operator("p4-q2", queueCondo);
        operator("p4-op-other", other);
        operator("p4-op-noshift", condo);
    }

    private UUID operator(String subject, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), Role.OPERATOR, null, condominium));
        return u.getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private void startShift(String who) throws Exception {
        int code = mvc.perform(post("/api/v1/operator/shifts/start").with(as(who))).andReturn().getResponse().getStatus();
        assertThat(code).isIn(201, 409); // 409: turno já aberto por outro teste da classe
    }

    private String createCall(UUID c, String who) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/condominiums/" + c + "/calls").with(as(who))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"callerName\":\"Visitante\"}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r).get("id").asString();
    }

    private JsonNode claim(String who) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/operator/calls/next").with(as(who))).andReturn();
        return r.getResponse().getStatus() == 200 ? body(r) : null;
    }

    private void closeCall(String who, UUID c, String id, String outcome, String just) throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + c + "/calls/" + id + "/close").with(as(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\",\"justification\":" + (just == null ? "null" : "\"" + just + "\"") + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void cannotTakeCallsWithoutShift() throws Exception {
        mvc.perform(post("/api/v1/operator/calls/next").with(as("p4-op-noshift"))).andExpect(status().isConflict());
    }

    @Test
    void residentCannotUseOperatorEndpoints() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p4-res"))).andExpect(status().isForbidden());
    }

    @Test
    void concurrentOperatorsNeverReceiveTheSameCall() throws Exception {
        startShift("p4-q1");
        startShift("p4-q2");
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            created.add(createCall(queueCondo, "p4-q1"));
        }
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String who = i % 2 == 0 ? "p4-q1" : "p4-q2";
            tasks.add(() -> {
                JsonNode c = claim(who);
                return c == null ? null : c.get("id").asString();
            });
        }
        List<String> claimed = runConcurrently(tasks).stream().filter(java.util.Objects::nonNull).toList();
        assertThat(claimed).hasSize(10).doesNotHaveDuplicates();
        assertThat(created).containsExactlyInAnyOrderElementsOf(claimed);
        assertThat(claim("p4-q1")).isNull();
    }

    @Test
    void operatorOnlyReceivesCallsFromAssignedCondominiums() throws Exception {
        startShift("p4-op-other");
        String id = createCall(condo, "p4-op1");
        JsonNode claimed = claim("p4-op-other");
        assertThat(claimed == null || !claimed.get("id").asString().equals(id)).isTrue();
        mvc.perform(post("/api/v1/condominiums/" + condo + "/calls").with(as("p4-op-other"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/operator/shifts/end").with(as("p4-op-other"))).andExpect(status().isOk());
    }

    @Test
    void sensitiveOutcomesRequireJustificationAndOnlyAssigneeCanAct() throws Exception {
        UUID stateCondo = condo;
        mvc.perform(post("/api/v1/condominiums/" + stateCondo + "/me/calls").with(as("p4-res"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"callerName\":\"Morador\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.source").value("RESIDENT_APP"));
        // op1 tem turno aberto (aberto no teste concorrente, se ainda estiver) — garante turno
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p4-op1")));
        JsonNode call = claim("p4-op1");
        assertThat(call).isNotNull();
        String id = call.get("id").asString();
        String url = "/api/v1/condominiums/" + stateCondo + "/calls/" + id + "/close";

        mvc.perform(post(url).with(as("p4-op2")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"RESOLVED\"}")).andExpect(status().isForbidden());
        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"ENTRY_GRANTED\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"BOGUS\"}")).andExpect(status().isBadRequest());
        closeCall("p4-op1", stateCondo, id, "ENTRY_GRANTED", "Morador confirmou por telefone");
        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"RESOLVED\"}")).andExpect(status().isConflict());

        mvc.perform(get("/api/v1/condominiums/" + stateCondo + "/calls/" + id + "/events").with(as("p4-op1")))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[1].type").value("CLAIMED"))
                .andExpect(jsonPath("$[2].type").value("CLOSED"))
                .andExpect(jsonPath("$[2].detail").value("ENTRY_GRANTED: Morador confirmou por telefone"));
    }

    @Test
    void transferRequiresEligibleOperatorAndEndingShiftRequiresNoActiveCalls() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p4-op1")));
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("p4-op2")));
        createCall(condo, "p4-op1");
        JsonNode call = claim("p4-op1");
        assertThat(call).isNotNull();
        String id = call.get("id").asString();
        String url = "/api/v1/condominiums/" + condo + "/calls/" + id + "/transfer";

        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toUserId\":\"" + UUID.randomUUID() + "\",\"reason\":\"x\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/operator/shifts/end").with(as("p4-op1"))).andExpect(status().isConflict());
        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toUserId\":\"" + op2Id + "\",\"reason\":\"fim de turno\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedUserId").value(op2Id.toString()));
        mvc.perform(post(url).with(as("p4-op1")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toUserId\":\"" + op2Id + "\",\"reason\":\"de novo\"}")).andExpect(status().isForbidden());
        closeCall("p4-op2", condo, id, "RESOLVED", null);
    }

    @Test
    void incidentsAreScopedAndResolvableOnce() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents").with(as("p4-op1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"HIGH\",\"title\":\"Portão travado\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = body(r).get("id").asString();
        mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents/" + id + "/resolve").with(as("p4-op-other")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents/" + id + "/resolve").with(as("p4-op1")))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents/" + id + "/resolve").with(as("p4-op1")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/condominiums/" + condo + "/incidents").with(as("p4-op1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"severity\":\"X\",\"title\":\"t\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onDutyOperatorsListsOnlyOperatorsWithOpenShiftOfThatCondominium() throws Exception {
        startShift("p4-op2");
        mvc.perform(get("/api/v1/condominiums/" + condo + "/operators/on-duty").with(as("p4-op1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.displayName=='p4-op2')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.displayName=='p4-op-other')]").isEmpty());
        mvc.perform(get("/api/v1/condominiums/" + condo + "/operators/on-duty").with(as("p4-op-other")))
                .andExpect(status().isNotFound());
    }

    @Test
    void overviewListsOnlyAuthorizedCondominiumsWithRealHealthAndConnectivity() throws Exception {
        mvc.perform(get("/api/v1/operator/overview").with(as("p4-op-other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("P4 Other"))
                .andExpect(jsonPath("$[0].deviceHealth").value("NO_DEVICES"))
                .andExpect(jsonPath("$[0].connectivity").value("NO_AGENT"));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return t.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
