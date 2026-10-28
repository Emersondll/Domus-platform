package br.com.domus.platform.operations;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.residents.entity.ResidentType;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/** Veículos, encomendas, reservas e convites/credenciais: regras de negócio, isolamento e concorrência. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CondominialOperationsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    UUID condoA, condoB, unitA101, unitA102, unitB;

    @BeforeAll
    void seed() {
        OperatorCompany company = companies.save(new OperatorCompany("P3 Company", "p3-00000000000"));
        condoA = condominiums.save(new Condominium(company.getId(), "P3 A", null)).getId();
        condoB = condominiums.save(new Condominium(company.getId(), "P3 B", null)).getId();
        Block blockA = blocks.save(new Block(condoA, "A"));
        Block blockB = blocks.save(new Block(condoB, "A"));
        unitA101 = units.save(new Unit(condoA, blockA.getId(), "101")).getId();
        unitA102 = units.save(new Unit(condoA, blockA.getId(), "102")).getId();
        unitB = units.save(new Unit(condoB, blockB.getId(), "101")).getId();
        resident("p3-res-101", condoA, unitA101);
        resident("p3-res-102", condoA, unitA102);
        resident("p3-res-b", condoB, unitB);
        staff("p3-operator", Role.OPERATOR, condoA);
        staff("p3-manager", Role.CONDO_MANAGER, condoA);
        staff("p3-operator-b", Role.OPERATOR, condoB);
    }

    private void resident(String subject, UUID condo, UUID unit) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, ResidentType.OWNER));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
    }

    private void staff(String subject, Role role, UUID condo) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condo));
    }

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(j -> j.subject(subject));
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String invitationJson(int maxUses, Instant from, Instant until) {
        return "{\"visitorName\":\"Visitante Teste\",\"kind\":\"VISITOR\",\"validFrom\":\"" + from
                + "\",\"validUntil\":\"" + until + "\",\"maxUses\":" + maxUses + "}";
    }

    private JsonNode invite(String subject, UUID condo, int maxUses) throws Exception {
        Instant now = Instant.now();
        MvcResult r = mvc.perform(post("/api/v1/condominiums/" + condo + "/me/invitations").with(as(subject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(maxUses, now.minusSeconds(60), now.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isCreated()).andReturn();
        return body(r);
    }

    private String validateBody(String token) {
        return "{\"token\":\"" + token + "\",\"entrance\":\"main-gate\"}";
    }

    @Test
    void residentManagesOwnVehiclesAndPlateIsUniquePerCondominium() throws Exception {
        String url = "/api/v1/condominiums/" + condoA + "/me/vehicles";
        mvc.perform(post(url).with(as("p3-res-101")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"abc-1d23\",\"model\":\"Fiat\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plate").value("ABC1D23"));
        mvc.perform(post(url).with(as("p3-res-102")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plate\":\"ABC1D23\"}")).andExpect(status().isConflict());
        mvc.perform(get(url).with(as("p3-res-102"))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(url).with(as("p3-res-101"))).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void residentOfAnotherCondominiumIsRejectedWith404() throws Exception {
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/vehicles").with(as("p3-res-b")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("p3-res-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(1, Instant.now(), Instant.now().plusSeconds(3600))))
                .andExpect(status().isNotFound());
    }

    @Test
    void parcelLifecycleVisibleOnlyToTheRightUnit() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/condominiums/" + condoA + "/parcels").with(as("p3-operator"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":\"" + unitA101 + "\",\"carrier\":\"Correios\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = body(created).get("id").asString();
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/parcels").with(as("p3-res-101")))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/me/parcels").with(as("p3-res-102")))
                .andExpect(jsonPath("$.totalElements").value(0));
        String pickup = "/api/v1/condominiums/" + condoA + "/parcels/" + id + "/pickup";
        mvc.perform(post(pickup).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickedUpByName\":\"Fulano\"}")).andExpect(jsonPath("$.status").value("PICKED_UP"));
        mvc.perform(post(pickup).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pickedUpByName\":\"Fulano\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/parcels").with(as("p3-operator-b"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\"" + unitA101 + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void parcelCannotTargetUnitOfAnotherCondominium() throws Exception {
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/parcels").with(as("p3-operator"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitId\":\"" + unitB + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlappingReservationsConflictEvenWhenConcurrent() throws Exception {
        MvcResult area = mvc.perform(post("/api/v1/condominiums/" + condoA + "/common-areas").with(as("p3-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Salão\",\"capacity\":40}"))
                .andExpect(status().isCreated()).andReturn();
        String areaId = body(area).get("id").asString();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        String payload = "{\"areaId\":\"" + areaId + "\",\"startAt\":\"" + start + "\",\"endAt\":\""
                + start.plus(4, ChronoUnit.HOURS) + "\"}";
        String url = "/api/v1/condominiums/" + condoA + "/me/reservations";

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (String who : List.of("p3-res-101", "p3-res-102")) {
            tasks.add(() -> mvc.perform(post(url).with(as(who)).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andReturn().getResponse().getStatus());
        }
        List<Integer> codes = runConcurrently(tasks);
        assertThat(codes).containsExactlyInAnyOrder(201, 409);

        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        mvc.perform(post(url).with(as("p3-res-101")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaId\":\"" + areaId + "\",\"startAt\":\"" + past
                                + "\",\"endAt\":\"" + past.plusSeconds(3600) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void concurrentOverlappingReservationsNeverDeadlockOverManyRounds() throws Exception {
        String url = "/api/v1/condominiums/" + condoA + "/me/reservations";
        for (int round = 0; round < 25; round++) {
            String areaId = body(mvc.perform(post("/api/v1/condominiums/" + condoA + "/common-areas").with(as("p3-manager"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rodada " + round + "\",\"capacity\":10}"))
                    .andExpect(status().isCreated()).andReturn()).get("id").asString();
            Instant start = Instant.now().plus(10 + round, ChronoUnit.DAYS);
            String payload = "{\"areaId\":\"" + areaId + "\",\"startAt\":\"" + start + "\",\"endAt\":\""
                    + start.plus(2, ChronoUnit.HOURS) + "\"}";
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (String who : List.of("p3-res-101", "p3-res-102")) {
                tasks.add(() -> mvc.perform(post(url).with(as(who)).contentType(MediaType.APPLICATION_JSON)
                        .content(payload)).andReturn().getResponse().getStatus());
            }
            assertThat(runConcurrently(tasks)).as("round " + round).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void singleUseCredentialIsGrantedExactlyOnceUnderConcurrency() throws Exception {
        String token = invite("p3-res-101", condoA, 1).get("token").asString();
        String url = "/api/v1/condominiums/" + condoA + "/access/validate";

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                MvcResult r = mvc.perform(post(url).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token))).andReturn();
                return body(r).get("granted").asBoolean() ? 1 : 0;
            });
        }
        int granted = runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum();
        assertThat(granted).isEqualTo(1);
        mvc.perform(post(url).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token)))
                .andExpect(jsonPath("$.granted").value(false)).andExpect(jsonPath("$.reason").value("EXHAUSTED"));
    }

    @Test
    void multiUseCredentialNeverExceedsItsQuota() throws Exception {
        String token = invite("p3-res-101", condoA, 3).get("token").asString();
        String url = "/api/v1/condominiums/" + condoA + "/access/validate";
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> body(mvc.perform(post(url).with(as("p3-operator"))
                    .contentType(MediaType.APPLICATION_JSON).content(validateBody(token))).andReturn())
                    .get("granted").asBoolean() ? 1 : 0);
        }
        assertThat(runConcurrently(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
    }

    @Test
    void revokedCredentialIsDenied() throws Exception {
        JsonNode inv = invite("p3-res-101", condoA, 5);
        String id = inv.get("id").asString();
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations/" + id + "/revoke")
                .with(as("p3-res-102"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations/" + id + "/revoke")
                .with(as("p3-res-101"))).andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/access/validate").with(as("p3-operator"))
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(inv.get("token").asString())))
                .andExpect(jsonPath("$.granted").value(false)).andExpect(jsonPath("$.reason").value("REVOKED"));
    }

    @Test
    void futureCredentialIsNotYetValidAndTamperedTokensAreRejected() throws Exception {
        Instant from = Instant.now().plus(1, ChronoUnit.HOURS);
        MvcResult r = mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("p3-res-101"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(1, from, from.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isCreated()).andReturn();
        String token = body(r).get("token").asString();
        String url = "/api/v1/condominiums/" + condoA + "/access/validate";
        mvc.perform(post(url).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(token))).andExpect(jsonPath("$.reason").value("NOT_YET_VALID"));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
        mvc.perform(post(url).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(tampered))).andExpect(jsonPath("$.reason").value("INVALID_TOKEN"));
        mvc.perform(post(url).with(as("p3-operator")).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody("not-a-token"))).andExpect(jsonPath("$.reason").value("INVALID_TOKEN"));
    }

    @Test
    void tokenIsScopedToItsCondominium() throws Exception {
        String token = invite("p3-res-101", condoA, 1).get("token").asString();
        mvc.perform(post("/api/v1/condominiums/" + condoB + "/access/validate").with(as("p3-operator-b"))
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(token)))
                .andExpect(jsonPath("$.granted").value(false)).andExpect(jsonPath("$.reason").value("INVALID_TOKEN"));
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/access/validate").with(as("p3-operator-b"))
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void residentCannotValidateCredentialsOrReadAttempts() throws Exception {
        String token = invite("p3-res-101", condoA, 1).get("token").asString();
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/access/validate").with(as("p3-res-101"))
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(token)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/access/attempts").with(as("p3-res-101")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitationRulesAreEnforcedAndAttemptsAreRecorded() throws Exception {
        Instant now = Instant.now();
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("p3-res-101"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(1, now, now.plus(30, ChronoUnit.DAYS))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/condominiums/" + condoA + "/me/invitations").with(as("p3-res-101"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationJson(500, now, now.plusSeconds(60))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/access/attempts").with(as("p3-operator")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/condominiums/" + condoA + "/visitors?sort=password").with(as("p3-operator")))
                .andExpect(status().isBadRequest());
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
