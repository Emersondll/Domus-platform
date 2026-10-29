package br.com.domus.platform.devices;

import br.com.domus.platform.AbstractIntegrationTest;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Vídeo ao vivo: sessão de visualização, token do servidor de mídia e limites por papel e condomínio. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CameraStreamIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired OperatorCompanyRepository companies;
    @Autowired CondominiumRepository condominiums;
    @Autowired BlockRepository blocks;
    @Autowired UnitRepository units;
    @Autowired ResidentRepository residents;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;
    @MockitoBean MediaServer media;

    UUID condo, otherCondo;

    @BeforeAll
    void seed() {
        OperatorCompany c = companies.save(new OperatorCompany("CS Company", "cs-00000000000"));
        condo = condominiums.save(new Condominium(c.getId(), "CS Main", null)).getId();
        otherCondo = condominiums.save(new Condominium(c.getId(), "CS Other", null)).getId();
        Block b = blocks.save(new Block(condo, "A"));
        UUID unit = units.save(new Unit(condo, b.getId(), "101")).getId();
        Resident r = residents.save(new Resident(condo, unit, "cs-res", null, null, ResidentType.OWNER));
        AppUser res = users.save(new AppUser("cs-res", "cs-res@example.invalid", "res"));
        memberships.save(Membership.forResident(res.getId(), condo, r.getId()));
        member("cs-manager", Role.CONDO_MANAGER, condo);
        member("cs-operator", Role.OPERATOR, condo);
        member("cs-other-operator", Role.OPERATOR, otherCondo);
    }

    private void member(String subject, Role role, UUID condominium) {
        AppUser u = users.save(new AppUser(subject, subject + "@example.invalid", subject));
        memberships.save(new Membership(u.getId(), role, null, condominium));
    }

    private static RequestPostProcessor as(String s) {
        return jwt().jwt(j -> j.subject(s));
    }

    private String devices() {
        return "/api/v1/condominiums/" + condo + "/devices";
    }

    private String registerCamera(String name, String config) throws Exception {
        String res = mvc.perform(post(devices()).with(as("cs-manager")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"Portaria\","
                                + "\"config\":" + config + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    private String mediaAuth(String action, String path, String query, String password) throws Exception {
        String body = "{\"action\":\"" + action + "\",\"path\":\"" + path + "\",\"query\":\"" + query
                + "\",\"password\":\"" + password + "\"}";
        return String.valueOf(mvc.perform(post("/internal/media/auth").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getStatus());
    }

    @Test
    void sessionIsIssuedToOperatorAndTokenOnlyWorksForThatCamera() throws Exception {
        String id = registerCamera("Cam Portaria", "{\"rtspUrl\":\"rtsp://localhost:8554/portaria\"}");
        String out = mvc.perform(post(devices() + "/" + id + "/stream-session").with(as("cs-operator")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists())
                .andReturn().getResponse().getContentAsString();
        verify(media).ensurePath(eq("cam-" + id), startsWith("rtsp://localhost:8554/portaria"));
        String url = out.replaceAll(".*\"hlsUrl\":\"([^\"]+)\".*", "$1");
        URI uri = URI.create(url);
        org.junit.jupiter.api.Assertions.assertEquals("/cam-" + id + "/index.m3u8", uri.getPath());
        String query = uri.getQuery();

        org.junit.jupiter.api.Assertions.assertEquals("200", mediaAuth("read", "cam-" + id, query, ""));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("read", "cam-" + UUID.randomUUID(), query, ""));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("read", "cam-" + id, "token=1.abc", ""));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("read", "cam-" + id, "", ""));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("read", "cam-" + id, "token=1." + query.substring(query.indexOf('.') + 1), ""));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("api", "cam-" + id, query, ""));
    }

    @Test
    void publishNeedsTheSecret() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("200", mediaAuth("publish", "cam-x", "", "test-only-publish-secret"));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("publish", "cam-x", "", "wrong"));
        org.junit.jupiter.api.Assertions.assertEquals("401", mediaAuth("publish", "cam-x", "", ""));
    }

    @Test
    void boundariesByRoleAndCondominium() throws Exception {
        String id = registerCamera("Cam Garagem", "{\"rtspUrl\":\"rtsp://localhost:8554/garagem\"}");
        String noVideo = registerCamera("Cam Sem Video", "{}");
        String url = devices() + "/" + id + "/stream-session";
        mvc.perform(post(url).with(as("cs-res"))).andExpect(status().isNotFound());
        mvc.perform(post(url).with(as("cs-other-operator"))).andExpect(status().isNotFound());
        mvc.perform(post(url)).andExpect(status().isUnauthorized());
        mvc.perform(post(devices() + "/" + noVideo + "/stream-session").with(as("cs-operator")))
                .andExpect(status().isConflict());
    }

    @Test
    void registrationRejectsUnsafeRtspUrls() throws Exception {
        for (String cfg : new String[]{"{\"rtspUrl\":\"rtsp://user:pass@localhost/x\"}",
                "{\"rtspUrl\":\"rtsp://169.254.169.254/x\"}", "{\"rtspUrl\":\"http://localhost/x\"}"}) {
            mvc.perform(post(devices()).with(as("cs-manager")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Ruim " + cfg.hashCode() + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\","
                                    + "\"entrance\":\"P\",\"config\":" + cfg + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    private void viewable(String id, boolean enabled, String as) throws Exception {
        mvc.perform(post(devices() + "/" + id + "/resident-viewable").with(as(as)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}")).andExpect(status().isOk());
    }

    @Test
    void residentSeesOnlyTheEntranceCamerasTheManagerReleased() throws Exception {
        String shown = registerCamera("Cam Entrada Res", "{\"rtspUrl\":\"rtsp://localhost:8554/entrada\"}");
        String hidden = registerCamera("Cam Interna Res", "{\"rtspUrl\":\"rtsp://localhost:8554/interna\"}");
        String me = "/api/v1/condominiums/" + condo + "/me/cameras";
        mvc.perform(post(devices() + "/" + shown + "/resident-viewable").with(as("cs-operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isForbidden());
        viewable(shown, true, "cs-manager");

        mvc.perform(get(me).with(as("cs-res"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + shown + "')].name").value("Cam Entrada Res"))
                .andExpect(jsonPath("$[?(@.id=='" + hidden + "')]").isEmpty());
        mvc.perform(post(me + "/" + shown + "/stream-session").with(as("cs-res")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists());
        mvc.perform(post(me + "/" + hidden + "/stream-session").with(as("cs-res"))).andExpect(status().isNotFound());
        // equipe e quem é de outro condomínio não usam o caminho do morador
        mvc.perform(get(me).with(as("cs-operator"))).andExpect(status().isNotFound());
        mvc.perform(get(me).with(as("cs-other-operator"))).andExpect(status().isNotFound());
        mvc.perform(get(me)).andExpect(status().isUnauthorized());

        viewable(shown, false, "cs-manager");
        mvc.perform(post(me + "/" + shown + "/stream-session").with(as("cs-res"))).andExpect(status().isNotFound());
    }

    @Test
    void permissionsTellEachRoleWhatItMayDoAndReleasingNotifiesResidents() throws Exception {
        String url = devices() + "/permissions";
        mvc.perform(get(url).with(as("cs-manager"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("MANAGE")).andExpect(jsonPath("$.canManage").value(true));
        mvc.perform(get(url).with(as("cs-operator"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("OPERATE")).andExpect(jsonPath("$.canManage").value(false));
        mvc.perform(get(url).with(as("cs-other-operator"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(as("cs-res"))).andExpect(status().isNotFound());

        String id = registerCamera("Cam Aviso Res", "{\"rtspUrl\":\"rtsp://localhost:8554/aviso\"}");
        String notes = "/api/v1/condominiums/" + condo + "/me/notifications";
        viewable(id, true, "cs-manager");
        mvc.perform(get(notes).with(as("cs-res"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.type=='CAMERA_RELEASED')].body").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Cam Aviso Res"))));
    }

    @Test
    void anyNumberOfCamerasCanBeReleasedAndDeactivatingWithdrawsIt() throws Exception {
        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = registerCamera("Cam Lim " + i, "{\"rtspUrl\":\"rtsp://localhost:8554/l" + i + "\"}");
        }
        // decisão do titular (D21): sem limite de quantidade; todas as cinco são liberadas
        for (String id : ids) {
            mvc.perform(post(devices() + "/" + id + "/resident-viewable").with(as("cs-manager"))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
        }
        // desativar a câmera retira a liberação
        mvc.perform(post(devices() + "/" + ids[0] + "/active").with(as("cs-manager")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.residentViewable").value(false));
    }

    @Test
    void simultaneousReleasesAreAllAccepted() throws Exception {
        OperatorCompany c = companies.save(new OperatorCompany("CS Race Company", "cs-race-0000"));
        UUID raceCondo = condominiums.save(new Condominium(c.getId(), "CS Race", null)).getId();
        member("cs-race-manager", Role.CONDO_MANAGER, raceCondo);
        String base = "/api/v1/condominiums/" + raceCondo + "/devices";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String res = mvc.perform(post(base).with(as("cs-race-manager")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Race " + i + "\",\"kind\":\"CAMERA\",\"adapter\":\"ONVIF\",\"entrance\":\"P\","
                                    + "\"config\":{\"rtspUrl\":\"rtsp://localhost:8554/r" + i + "\"}}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            ids.add(res.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String id : ids) {
            Callable<Integer> call = () -> {
                go.await();
                return mvc.perform(post(base + "/" + id + "/resident-viewable").with(as("cs-race-manager"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        int ok = 0, conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get();
            if (code == 200) ok++;
            else if (code == 409) conflict++;
        }
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(ok).isEqualTo(8);
        org.assertj.core.api.Assertions.assertThat(conflict).isZero();
    }
}
