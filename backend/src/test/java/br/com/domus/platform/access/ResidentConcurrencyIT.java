package br.com.domus.platform.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * Moradores e portaria disputando o mesmo recurso ao mesmo tempo. Cada teste dispara as requisições juntas (trava de partida)
 * e confere o invariante no banco: uma decisão, uma retirada, uma entrada, uma chamada em aberto por unidade.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResidentConcurrencyIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condo, unit;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("Conc Company", "conc-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "Conc Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        for (String s : List.of("conc-res-a", "conc-res-b", "conc-res-c")) {
            Resident r = residents.save(new Resident(condo, unit, s, null, null, ResidentType.OWNER));
            AppUser u = users.save(new AppUser(s, s + "@example.invalid", s));
            memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
        }
        AppUser op = users.save(new AppUser("conc-operator", "conc-operator@example.invalid", "op"));
        memberships.save(new Membership(op.getId(), Role.OPERATOR, null, condo));
        AppUser mgr = users.save(new AppUser("conc-manager", "conc-manager@example.invalid", "mgr"));
        memberships.save(new Membership(mgr.getId(), Role.CONDO_MANAGER, null, condo));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String base() {
        return "/api/v1/condominiums/" + condo;
    }

    /** Dispara {@code n} chamadas juntas e devolve os códigos HTTP. */
    private List<Integer> together(int n, IntFunction<Callable<Integer>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Integer> t = task.apply(i);
            futures.add(pool.submit(() -> {
                go.await();
                return t.call();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : futures) {
            codes.add(f.get());
        }
        pool.shutdown();
        return codes;
    }

    private String body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString();
    }

    private String newVisitRequest(String name) throws Exception {
        return json.readTree(body(mvc.perform(post(base() + "/visit-requests").with(as("conc-operator"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitId\":\"" + unit + "\",\"visitorName\":\"" + name + "\",\"kind\":\"VISITOR\"}"))))
                .get("id").asString();
    }

    @Test
    void residentsOfTheSameUnitOpeningCallsTogetherLeaveOnlyOneOpenCall() throws Exception {
        List<String> who = List.of("conc-res-a", "conc-res-b", "conc-res-c");
        List<Integer> codes = together(12, i -> () -> mvc.perform(post(base() + "/me/calls").with(as(who.get(i % 3)))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
        assertThat(codes).allMatch(c -> c < 300);
        assertThat(jdbc.sql("SELECT count(*) FROM service_call WHERE condominium_id = :c AND unit_id = :u AND source = 'RESIDENT_APP' AND status <> 'CLOSED'")
                .param("c", condo).param("u", unit).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void panicPressedTogetherRaisesASingleAlert() throws Exception {
        together(10, i -> () -> mvc.perform(post(base() + "/me/panic").with(as("conc-res-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
        assertThat(jdbc.sql("SELECT count(*) FROM service_call WHERE condominium_id = :c AND unit_id = :u AND source = 'PANIC' AND status <> 'CLOSED'")
                .param("c", condo).param("u", unit).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void conflictingDecisionsOnTheSameVisitRequestLetOnlyOneWin() throws Exception {
        String id = newVisitRequest("Visita Concorrente");
        List<String> who = List.of("conc-res-a", "conc-res-b", "conc-res-c");
        List<Integer> codes = together(9, i -> () -> mvc.perform(post(base() + "/me/visit-requests/" + id
                + (i % 2 == 0 ? "/approve" : "/deny")).with(as(who.get(i % 3)))).andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("decisões aceitas: %s", codes).isEqualTo(1);
        assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(8);
    }

    @Test
    void sameParcelCanOnlyBePickedUpOnce() throws Exception {
        String parcel = json.readTree(body(mvc.perform(post(base() + "/parcels").with(as("conc-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\"" + unit + "\",\"carrier\":\"X\"}"))))
                .get("id").asString();
        List<Integer> codes = together(10, i -> () -> mvc.perform(post(base() + "/parcels/" + parcel + "/pickup")
                .with(as("conc-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickedUpByName\":\"pessoa" + i + "\"}")).andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("retiradas aceitas: %s", codes).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'parcel.pickup' AND resource_id = :id")
                .param("id", parcel).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sameApprovedVisitCanOnlyHaveOneEntryRegistered() throws Exception {
        String id = newVisitRequest("Entrada Concorrente");
        mvc.perform(post(base() + "/me/visit-requests/" + id + "/approve").with(as("conc-res-a")));
        List<Integer> codes = together(8, i -> () -> mvc.perform(post(base() + "/visit-requests/" + id + "/entry")
                .with(as("conc-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entrance\":\"portaria\"}")).andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 201).count()).as("entradas aceitas: %s", codes).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM access_attempt WHERE visit_request_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void readsStayConsistentWhileResidentsWrite() throws Exception {
        List<Integer> codes = together(12, i -> () -> i % 2 == 0
                ? mvc.perform(get(base() + "/me/invitations").with(as("conc-res-a"))).andReturn().getResponse().getStatus()
                : mvc.perform(post(base() + "/me/invitations").with(as("conc-res-b")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visitorName\":\"V\",\"kind\":\"VISITOR\",\"validFrom\":\"" + java.time.Instant.now().minusSeconds(30)
                                + "\",\"validUntil\":\"" + java.time.Instant.now().plusSeconds(600) + "\",\"maxUses\":1}"))
                        .andReturn().getResponse().getStatus());
        assertThat(codes).allMatch(c -> c < 300);
    }

    @Test
    void sameCallCanOnlyBeClosedOnceAndNotifiesTheResidentOnce() throws Exception {
        mvc.perform(post("/api/v1/operator/shifts/start").with(as("conc-operator")));
        String call = json.readTree(body(mvc.perform(post(base() + "/calls").with(as("conc-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\"" + unit + "\"}")))).get("id").asString();
        UUID callId = UUID.fromString(call);
        jdbc.sql("UPDATE service_call SET status = 'ASSIGNED', assigned_user_id = (SELECT id FROM app_user WHERE external_subject = 'conc-operator') WHERE id = :id AND status = 'QUEUED'")
                .param("id", callId).update();
        List<Integer> codes = together(8, i -> () -> mvc.perform(post(base() + "/calls/" + call + "/close")
                .with(as("conc-operator")).contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED\"}"))
                .andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("encerramentos aceitos: %s", codes).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM call_event WHERE call_id = :id AND type = 'CLOSED'")
                .param("id", callId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sameExitCanOnlyBeRegisteredOnce() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        String token = json.readTree(body(mvc.perform(post(base() + "/me/invitations").with(as("conc-res-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitorName\":\"Saida\",\"kind\":\"VISITOR\",\"validFrom\":\"" + now.minusSeconds(30)
                        + "\",\"validUntil\":\"" + now.plusSeconds(600) + "\",\"maxUses\":1}")))).get("token").asString();
        String attempt = json.readTree(body(mvc.perform(post(base() + "/access/validate").with(as("conc-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\",\"entrance\":\"portaria\"}"))))
                .get("attemptId").asString();
        List<Integer> codes = together(8, i -> () -> mvc.perform(post(base() + "/access/entries/" + attempt + "/exit")
                .with(as("conc-operator"))).andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("saídas aceitas: %s", codes).isEqualTo(1);
    }

    @Test
    void sameIncidentCanOnlyBeResolvedOnce() throws Exception {
        String incident = json.readTree(body(mvc.perform(post(base() + "/incidents").with(as("conc-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"severity\":\"LOW\",\"title\":\"Conc\"}"))))
                .get("id").asString();
        List<Integer> codes = together(6, i -> () -> mvc.perform(post(base() + "/incidents/" + incident + "/resolve")
                .with(as("conc-operator")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("resoluções aceitas: %s", codes).isEqualTo(1);
    }

    @Test
    void sameReservationCanOnlyBeCancelledOnce() throws Exception {
        String area = json.readTree(body(mvc.perform(post(base() + "/common-areas").with(as("conc-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Salao Conc\",\"capacity\":5}")))).get("id").asString();
        java.time.Instant start = java.time.Instant.now().plus(3, java.time.temporal.ChronoUnit.DAYS);
        String reservation = json.readTree(body(mvc.perform(post(base() + "/me/reservations").with(as("conc-res-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"areaId\":\"" + area + "\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plusSeconds(3600) + "\"}"))))
                .get("id").asString();
        List<Integer> codes = together(6, i -> () -> mvc.perform(post(base() + "/me/reservations/" + reservation + "/cancel")
                .with(as("conc-res-a"))).andReturn().getResponse().getStatus());
        assertThat(codes.stream().filter(c -> c == 200).count()).as("cancelamentos aceitos: %s", codes).isEqualTo(1);
    }
}
