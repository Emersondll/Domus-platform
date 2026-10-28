package br.com.domus.platform.devices;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * B12 e B19: a sessão de vídeo existe no servidor e pode ser revogada (o token sozinho não basta), há teto por usuário, e
 * dependentes não veem câmeras nem recebem o aviso de câmera liberada.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CameraSessionRevocationIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @MockitoBean MediaServer media;

    UUID condo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("SR Company", "sr-0000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "SR Main", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        resident("sr-adult", unit, ResidentType.OWNER);
        resident("sr-dependent", unit, ResidentType.DEPENDENT);
        staff("sr-manager", Role.CONDO_MANAGER, null);
        staff("sr-operator", Role.OPERATOR, null);
        staff("sr-admin", Role.COMPANY_ADMIN, c.getId());
    }

    private void resident(String subject, UUID unit, ResidentType type) {
        Resident r = residents.save(new Resident(condo, unit, subject, null, null, type));
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(Membership.forResident(u.getId(), condo, r.getId()));
    }

    private UUID staff(String subject, Role role, UUID company) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        return memberships.save(new Membership(u.getId(), role, company, company == null ? condo : null)).getId();
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String devices() {
        return "/api/v1/condominiums/" + condo + "/devices";
    }

    private String camera(String name) throws Exception {
        String res = mvc.perform(post(devices()).with(as("sr-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"P\","
                                + "\"config\":{\"rtspUrl\":\"rtsp://localhost:8554/" + name.hashCode() + "\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private String openStaff(String user, String cameraId) throws Exception {
        String out = mvc.perform(post(devices() + "/" + cameraId + "/stream-session").with(as(user)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return URI.create(out.replaceAll(".*\"hlsUrl\":\"([^\"]+)\".*", "$1")).getQuery();
    }

    private String openResident(String user, String cameraId) throws Exception {
        String out = mvc.perform(post("/api/v1/condominiums/" + condo + "/me/cameras/" + cameraId + "/stream-session")
                        .with(as(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return URI.create(out.replaceAll(".*\"hlsUrl\":\"([^\"]+)\".*", "$1")).getQuery();
    }

    private int mediaAuth(String cameraId, String query) throws Exception {
        String body = "{\"action\":\"read\",\"path\":\"cam-" + cameraId + "\",\"query\":\"" + query + "\",\"password\":\"\"}";
        return mvc.perform(post("/internal/media/auth").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private void release(String cameraId, boolean enabled) throws Exception {
        mvc.perform(post(devices() + "/" + cameraId + "/resident-viewable").with(as("sr-manager"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}")).andExpect(status().isOk());
    }

    @Test
    void aValidSignatureIsNotEnoughWithoutALiveServerSession() throws Exception {
        String id = camera("Cam Forjada");
        String q = openStaff("sr-operator", id);
        assertEquals(200, mediaAuth(id, q));
        // mesma assinatura e prazo, mas outra sessão: a assinatura deixa de bater
        String forged = q.replaceFirst("token=[^.]+", "token=" + UUID.randomUUID());
        assertEquals(401, mediaAuth(id, forged));
        // token de uma câmera não vale em outra, mesmo com sessão ativa
        String other = camera("Cam Outra Forjada");
        assertEquals(401, mediaAuth(other, q));
    }

    @Test
    void deactivatingTheCameraEndsLiveSessionsImmediately() throws Exception {
        String id = camera("Cam Desativada");
        String q = openStaff("sr-operator", id);
        assertEquals(200, mediaAuth(id, q));
        mvc.perform(post(devices() + "/" + id + "/active").with(as("sr-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk());
        assertEquals(401, mediaAuth(id, q));
    }

    @Test
    void withdrawingTheReleaseEndsOnlyTheResidentSessions() throws Exception {
        String id = camera("Cam Retirada");
        release(id, true);
        String staff = openStaff("sr-operator", id);
        String resident = openResident("sr-adult", id);
        assertEquals(200, mediaAuth(id, resident));
        release(id, false);
        assertEquals(401, mediaAuth(id, resident));
        assertEquals(200, mediaAuth(id, staff));
    }

    @Test
    void revokingTheMembershipEndsTheVideoTheUserAlreadyHad() throws Exception {
        String id = camera("Cam Vinculo");
        UUID membership = staff("sr-leaving", Role.OPERATOR, null); // próprio operador: a revogação não afeta os outros testes
        String q = openStaff("sr-leaving", id);
        assertEquals(200, mediaAuth(id, q));
        mvc.perform(post("/api/v1/condominiums/" + condo + "/members/" + membership + "/revoke").with(as("sr-admin")))
                .andExpect(status().isNoContent());
        assertEquals(401, mediaAuth(id, q));
    }

    @Test
    void aUserKeepsAtMostTwelveLiveSessionsAndTheOldestIsDropped() throws Exception {
        String id = camera("Cam Teto");
        String first = openStaff("sr-manager", id);
        assertEquals(200, mediaAuth(id, first));
        String last = null;
        for (int i = 0; i < 12; i++) {
            last = openStaff("sr-manager", id);
        }
        assertEquals(401, mediaAuth(id, first));
        assertEquals(200, mediaAuth(id, last));
    }

    @Test
    void dependentsDoNotSeeReleasedCamerasOrGetTheReleaseNotice() throws Exception {
        String id = camera("Cam Dependente");
        release(id, true);
        String me = "/api/v1/condominiums/" + condo + "/me/cameras";
        mvc.perform(get(me).with(as("sr-adult"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].name").value("Cam Dependente"));
        mvc.perform(get(me).with(as("sr-dependent"))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post(me + "/" + id + "/stream-session").with(as("sr-dependent"))).andExpect(status().isNotFound());

        String notes = "/api/v1/condominiums/" + condo + "/me/notifications";
        mvc.perform(get(notes).with(as("sr-adult"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='CAMERA_RELEASED')]").isNotEmpty());
        mvc.perform(get(notes).with(as("sr-dependent"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='CAMERA_RELEASED')]").isEmpty());
    }

    @Test
    void simultaneousOpeningsByTheSameUserNeverFailAndStayUnderTheCap() throws Exception {
        String id = camera("Cam Concorrente");
        UUID userId = users.findByExternalSubject("sr-operator").orElseThrow().getId();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(devices() + "/" + id + "/stream-session").with(as("sr-operator")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<Integer> f : results) {
            assertEquals(200, f.get(), "abrir sessões ao mesmo tempo não pode dar deadlock nem erro");
        }
        pool.shutdown();
        Integer active = jdbc.sql("SELECT count(*) FROM camera_view_session WHERE user_id = :u AND revoked_at IS NULL AND expires_at > now()")
                .param("u", userId).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(active <= 12, "o teto de 12 sessões ativas vale também sob concorrência (ativas: " + active + ")");
    }
}
